(ns onyx.peer.queryable-state-manager
  (:require [clojure.core.async :refer [chan close! poll! >!!]]
            [onyx.state.serializers.utils]
            [com.stuartsierra.component :as component]
            [onyx.peer.window-state :as ws]
            [onyx.state.protocol.db :as db]
            [taoensso.timbre :refer [fatal info]])
  (:import [java.util.concurrent.locks LockSupport]))

(defn state-key [replica-version event]
  [(:onyx.core/job-id event)
   replica-version
   (:onyx.core/task event)
   (:onyx.core/slot-id event)])

(defmulti process-store 
  (fn [[cmd] _ _]
    (println "CMD" cmd)
    cmd)) 

(defmethod process-store :created-db
  [[_ replica-version event exported] state peer-config]
  (let [serializers (onyx.state.serializers.utils/event->state-serializers event)]
    (swap! state 
           assoc 
           (state-key replica-version event)
           {:state-indices (ws/state-indices event)
            :db (db/open-db-reader peer-config exported serializers)})))

(defmethod process-store :drop-db 
  [[_ event] state peer-config]
  (let [serializers (onyx.state.serializers.utils/event->state-serializers event)
        k (state-key replica-version event)
        store (get @state k)]
    (db/close! store)
    (swap! state dissoc k)))

(defn processing-loop [peer-config shutdown state ch]
  (loop []
    (when-not @shutdown
      (if-let [cmd (poll! ch)]
        (process-store cmd state peer-config)
        (LockSupport/parkNanos (* 10 1000000)))
      (recur))))

(defrecord OnyxStateStoreGroup [peer-config ch state shutdown]
  component/Lifecycle
  (start [this]
    (let [shutdown (atom false)
          state (atom {})
          ch (chan 1000)
          fut (future (processing-loop peer-config shutdown state ch))]
      (assoc this 
             :fut fut
             :shutdown shutdown
             :ch ch 
             :state state)))
  (stop [this]
    (close! ch)
    (reset! shutdown true)
    (future-cancel (:fut this))
    (assoc this :ch nil :state nil :state nil :fut nil)))

(defn new-state-store-group [peer-config]
  (map->OnyxStateStoreGroup {:peer-config peer-config}))
