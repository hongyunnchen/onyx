(ns ^:no-doc onyx.messaging.immutable-messenger
  (:require [clojure.set :refer [subset?]]
            [com.stuartsierra.component :as component]
            [taoensso.timbre :refer [fatal info debug] :as timbre]
            [onyx.types :as t :refer [->MonitorEventBytes]]
            [onyx.messaging.protocols.messenger :as m]))

(defrecord ImmutableMessagingPeerGroup []
  m/MessengerGroup
  (peer-site [messenger peer-id]
    {})

  component/Lifecycle
  (start [component]
    component)

  (stop [component]
    component))

(defn immutable-peer-group [opts]
  (map->ImmutableMessagingPeerGroup {:opts opts}))

(defmethod m/assign-task-resources :immutable-messaging
  [replica peer-id task-id peer-site peer-sites]
  {})

(defmethod m/get-peer-site :immutable-messaging
  [replica peer]
  {})

(defn update-first-subscriber [messenger f]
  (update-in messenger [:subscriptions (:id messenger) 0] f))

(defn set-ticket [messenger {:keys [src-peer-id dst-task-id slot-id]} ticket]
  (assoc-in messenger [:tickets src-peer-id dst-task-id slot-id] ticket))

(defn write [messenger {:keys [src-peer-id dst-task-id slot-id] :as task-slot} message]
  (update-in messenger 
             [:message-state src-peer-id dst-task-id slot-id]
             (fn [messages] 
               (conj (vec messages) message))))

(defn rotate [xs]
  (if (seq xs)
    (conj (into [] (rest xs)) (first xs))
    xs))

(defn is-next-barrier? [messenger barrier]
  (assert (m/replica-version messenger))
  (and (= (m/replica-version messenger) (:replica-version barrier))
       (= (inc (m/epoch messenger)) (:epoch barrier))))

(defn found-next-barrier? [messenger {:keys [barrier] :as subscriber}]
  ;(info "barrier" (into {} barrier) "vs " (m/replica-version messenger))
  (and (is-next-barrier? messenger barrier) 
       (not (:emitted? barrier))))

(defn unblocked? [messenger {:keys [barrier] :as subscriber}]
  (and (= (m/replica-version messenger) (:replica-version barrier))
       (= (m/epoch messenger) (:epoch barrier))
       (:emitted? (:barrier subscriber))))

(defn get-message [messenger {:keys [src-peer-id dst-task-id slot-id] :as subscriber} ticket]
  (get-in messenger [:message-state src-peer-id dst-task-id slot-id ticket]))

(defn get-messages [messenger {:keys [src-peer-id dst-task-id slot-id] :as subscriber}]
  (get-in messenger [:message-state src-peer-id dst-task-id slot-id]))

(defn messenger->subscriptions [messenger]
  (get-in messenger [:subscriptions (:id messenger)]))

(defn rotate-subscriptions [messenger]
  (update-in messenger [:subscriptions (:id messenger)] rotate))

(defn curr-ticket [messenger {:keys [src-peer-id dst-task-id slot-id] :as subscriber}]
  (get-in messenger [:tickets src-peer-id dst-task-id slot-id]))

(defn next-barrier [messenger {:keys [src-peer-id dst-task-id slot-id position] :as subscriber} max-index]
  #_(let [missed-indices (range (inc position) (inc max-index))] 
    (->> missed-indices
         (map (fn [idx] 
                [idx (get-in messenger [:message-state src-peer-id dst-task-id slot-id idx])]))
         (filter (fn [[idx m]]
                   (and (barrier? m)
                        (is-next-barrier? messenger m))))
         first)))

(defn barrier->str [barrier]
  (str "B: " [(:replica-version barrier) (:epoch barrier)]))

(defn subscriber->str [subscriber]
  (str "S: " [(:src-peer-id subscriber) (:dst-task-id subscriber)]
       " STATE: " (barrier->str (:barrier subscriber))))

(defn take-messages [messenger subscriber]
  #_(let [ticket (curr-ticket messenger subscriber) 
        next-ticket (inc ticket)
        message (get-message messenger subscriber ticket)
        skip-to-barrier? (or (nil? (:barrier subscriber))
                             (and (< (inc (:position subscriber)) ticket) 
                                  (unblocked? messenger subscriber)))] 
    ;(println "Message is " message "Unblocked? " (unblocked? messenger subscriber))
    (cond skip-to-barrier?
          ;; Skip up to next barrier so we're aligned again, 
          ;; but don't move past actual messages that haven't been read
          (let [max-index (if (nil? (:barrier subscriber)) 
                            (dec (count (get-messages messenger subscriber)))
                            ticket)
                [position next-barrier] (next-barrier messenger subscriber max-index)]
            (debug (:id messenger) (subscriber->str subscriber) (barrier->str next-barrier))
            {:ticket (if position 
                       (max ticket (inc position))
                       ticket)
             :subscriber (if next-barrier 
                           (assoc subscriber :position position :barrier next-barrier)
                           subscriber)})

          ;; We're on the correct barrier so we can read messages
          (and message 
               (barrier? message) 
               (is-next-barrier? messenger message))
          ;; If a barrier, then update your subscriber's barrier to next barrier
          {:ticket next-ticket
           :subscriber (assoc subscriber :position ticket :barrier message)}

          ;; Skip over outdated barrier, and block subscriber until we hit the right barrier
          (and message 
               (barrier? message) 
               (> (m/replica-version messenger)
                  (:replica-version message)))
          {:ticket next-ticket
           :subscriber (assoc subscriber :position ticket :barrier nil)}

          (and message 
               (unblocked? messenger subscriber) 
               (message? message))
          ;; If it's a message, update the subscriber position
          {:message (:payload message)
           :ticket next-ticket
           :subscriber (assoc subscriber :position ticket)}

          :else
          ;; Either nothing to read or we're past the current barrier, do nothing
          {:subscriber subscriber})))

(defn set-barrier-emitted [subscriber]
  (assoc-in subscriber [:barrier :emitted?] true))

(defn reset-messenger [messenger id]
  (-> messenger 
      (assoc-in [:subscriptions id] [])
      (assoc-in [:publications id] [])))

(defn add-to-subscriptions [subscriptions sub-info]
  (conj (or subscriptions []) 
        (-> sub-info 
            (select-keys [:src-peer-id :dst-task-id :slot-id])
            (assoc :position -1))))

(defn remove-from-subscriptions [subscribers sub-info]
  (filterv (fn [s] 
             (not= (select-keys sub-info [:src-peer-id :dst-task-id :slot-id]) 
                   (select-keys s [:src-peer-id :dst-task-id :slot-id])))
           subscribers))

(defrecord ImmutableMessenger
  [peer-group id replica-version epoch message-state publishers subscriber]
  component/Lifecycle
  (start [component]
    component)

  (stop [component]
    component)

  m/Messenger

  (publishers [messenger]
    (get publishers id))

  (subscriber [messenger]
    (get subscriber id))

  (update-subscriber
    [messenger sub-infos]
    (throw (Exception. "need to reconcile"))
    (let [sub-info :FIIII] 
      (-> messenger 
        (update-in [:tickets (:src-peer-id sub-info) (:dst-task-id sub-info) (:slot-id sub-info)] 
                   #(or % 0))
        (update-in [:subscriptions id] add-to-subscriptions sub-info))))

  ; (remove-subscription
  ;   [messenger sub-info]
  ;   (-> messenger 
  ;       (update-in [:subscriptions id] remove-from-subscriptions sub-info)))

  (update-publishers
    [messenger pub-infos]
    (throw (Exception. "need to reconcile"))
    (let [pub-info :FIIII] 
      (update-in messenger
               [:publications id] 
               (fn [pbs] 
                 (assert (= id (:src-peer-id pub-info)) [id (:src-peer-id pub-info)] )
                 (conj (or pbs []) 
                       (select-keys pub-info [:src-peer-id :dst-task-id :slot-id]))))))

  ; (remove-publication
  ;   [messenger pub-info]
  ;   (update-in messenger
  ;              [:publications id] 
  ;              (fn [pp] 
  ;                (filterv (fn [p] 
  ;                           (not= (select-keys pub-info [:src-peer-id :dst-task-id :slot-id]) 
  ;                                 (select-keys p [:src-peer-id :dst-task-id :slot-id])))
  ;                         pp))))

  (set-replica-version! [messenger replica-version]
    (-> messenger 
        (assoc-in [:replica-version id] replica-version)
        (update-in [:subscriptions id] 
                   (fn [ss] (mapv #(assoc % :barrier nil) ss)))
        (m/set-epoch! 0)))

  (replica-version [messenger]
    (get-in messenger [:replica-version id]))

  (epoch [messenger]
    (get epoch id))

  (set-epoch! 
    [messenger epoch]
    (assoc-in messenger [:epoch id] epoch))

  (next-epoch!
    [messenger]
    (update-in messenger [:epoch id] inc))

  (poll [messenger]
    #_(let [subscriber (first (messenger->subscriptions messenger))
          {:keys [message ticket] :as result} (take-messages messenger subscriber)] 
      (debug (:id messenger) "MSG:" (if message message "nil") "New sub:" (subscriber->str (:subscriber result)))
      (cond-> (assoc messenger :message nil)
        ticket (set-ticket subscriber ticket)
        true (update-first-subscriber (constantly (:subscriber result)))
        true (rotate-subscriptions)
        message (assoc :message message))))

  (offer-barrier [messenger publication]
    (onyx.messaging.protocols.messenger/offer-barrier messenger publication {}))

  (offer-barrier [messenger publication barrier-opts]
    #_(write messenger 
           publication 
           (merge (->Barrier (m/replica-version messenger) (m/epoch messenger) id (:dst-task-id publication)) 
                  barrier-opts))))

(defn immutable-messenger [peer-group]
  (map->ImmutableMessenger {:peer-group peer-group}))
