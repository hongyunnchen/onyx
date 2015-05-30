(ns ^:no-doc onyx.peer.function
    (:require [clojure.core.async :refer [chan >! go alts!! close! timeout]]
              [onyx.static.planning :refer [find-task]]
              [onyx.messaging.acking-daemon :as acker]
              [onyx.peer.pipeline-extensions :as p-ext]
              [onyx.peer.operation :as operation]
              [onyx.extensions :as extensions]
              [taoensso.timbre :as timbre :refer [debug info]]
              [onyx.types :refer [->Leaf]]
              [dire.core :refer [with-post-hook!]])
    (:import [java.util UUID]))

(defmethod p-ext/read-batch :default
  [{:keys [onyx.core/messenger] :as event}]
  {:onyx.core/batch (onyx.extensions/receive-messages messenger event)})

(defn apply-fn
  [{:keys [onyx.core/params] :as event} segment]
  (if-let [f (:onyx.core/fn event)]
    (operation/apply-function f params segment)
    segment))

(defn filter-by-route [messages task-name]
  (->> messages
       (filter (fn [msg] (some #{task-name} (:flow (:routes msg)))))
       (map #(dissoc % :routes :hash-group))))

(defn into-transient [coll vs]
  (loop [rs (seq vs) updated-coll coll]
    (if rs 
      (recur (next rs) 
             (conj! updated-coll (first rs)))
      updated-coll)))

(defn fast-concat [vvs]
  (loop [vs (seq vvs) coll (transient [])]
    (if vs
      (recur (next vs) 
             (into-transient coll (first vs)))
      (persistent! coll))))

;; needs a performance boost
(defn build-segments-to-send [leaves]
  (->> leaves
       (map (fn [{:keys [routes ack-vals hash-group message] :as leaf}]
              (if (= :retry (:action routes))
                []
                (map (fn [route ack-val]
                       (->Leaf (:message leaf)
                               (:id leaf)
                               (:acker-id leaf)
                               (:completion-id leaf)
                               ack-val
                               nil
                               route
                               nil
                               (get hash-group route)))
                     (:flow routes) 
                     ack-vals))))
       fast-concat))

(defn pick-peer [id active-peers hash-group max-downstream-links]
  (when-not (empty? active-peers)
    (if hash-group
      (nth active-peers
           (mod (hash hash-group)
                (count active-peers)))
      (rand-nth (operation/select-n-peers id active-peers max-downstream-links)))))

;; Needs a performance boost
(defmethod p-ext/write-batch :default
  [{:keys [onyx.core/id onyx.core/results onyx.core/messenger onyx.core/job-id onyx.core/max-downstream-links] :as event}]
  (let [leaves (fast-concat (map :leaves results))]
    (when-not (empty? leaves)
      (let [egress-tasks (:egress-ids (:onyx.core/serialized-task event))
            replica @(:onyx.core/replica event)
            peer-state (:peer-state replica)
            segments (build-segments-to-send leaves)
            groups (group-by :route segments)
            allocations (get (:allocations replica) job-id)]
        (doall 
          (map (fn [[route segs]]
                 (let [peers (get allocations (get egress-tasks route))
                       active-peers (filter #(= (get peer-state %) :active) peers)
                       groups-hash (group-by :hash-group segs)]
                   (map (fn [[hash-group segs*]]
                          (when-let [target (pick-peer id active-peers hash-group max-downstream-links)]
                            (let [link (operation/peer-link event target)]
                              (onyx.extensions/send-messages messenger event link segs*))))
                        groups-hash)))
               groups))
        {}))))
