(ns kafka-component.mock
  (:require [com.stuartsierra.component :as component]
            [clojure.core.async :as async :refer [>!! <!! chan alt!! timeout close! poll! go-loop]]
            [kafka-component.core :as core])
  (:import [org.apache.kafka.clients.producer Producer ProducerRecord RecordMetadata Callback]
           [org.apache.kafka.clients.consumer Consumer ConsumerRecord ConsumerRecords]
           [org.apache.kafka.common TopicPartition]
           [org.apache.kafka.common.errors WakeupException]
           [java.lang Integer]
           [java.util Collection]
           [java.util.regex Pattern]))

;; structure of broker-state:
;; {"sample-topic" [{:messages [] :watchers chan} {:messages [] :watchers chan}]}
(def broker-state (atom {}))
(def broker-lock (Object.))

;; (def consumers (atom {"group.id" [{:consumer-id 1 :topics ["test"]} {:consumer-id 2 :topics ["test"]}]
;;                       "group2" [{:topics ["test"]}]
;;                       ["group-1" "topic-1"] [:consumer-1 :consumer-2]
;;                       ["group-1" "topic-2"] [:consumer-1 :consumer-2]
;;
;;                       {tps [{:consumer-group 1 :consumer c :committed-offset 12}]}
;;                       }))

(def buffer-size 20)
(def default-num-partitions 2)

(defn reset-state! []
  (locking broker-lock
    (reset! broker-state {})))

(defn fixture-reset-state! [f]
  (reset-state!)
  (f))

(defn create-topic
  ([] (create-topic default-num-partitions))
  ([num-partitions]
   (into [] (repeatedly num-partitions (constantly {:messages [] :watchers (chan buffer-size)})))))

(defn ensure-topic [broker-state topic]
  (if (broker-state topic)
    broker-state
    (assoc broker-state topic (create-topic))))

(defn close-mock [state]
  (assoc state :conn-open? false))

(defn ->topic-partition [topic partition]
  (TopicPartition. topic partition))

(defn record->topic-partition [record]
  (TopicPartition. (.topic record) (.partition record)))

(defn read-offsets [grouped-messages]
  (into {} (for [[topic-partition msg-list] grouped-messages]
             [topic-partition (inc (.offset (last msg-list)))])))

(defn max-poll-records [config]
  (if-let [max-poll-records-str (config "max.poll.records")]
    (do
      (assert String (type max-poll-records-str))
      (Integer/parseInt max-poll-records-str))
    Integer/MAX_VALUE))

;; TODO: support grabbing last committed offset and only use earliest/latest/none if there are no committed offsets
;; TODO: support "none"
;; TODO: anything other than earliest, latest, none is to throw an exception
(defn get-offset [broker-state topic partition config]
  (let [latest-offset (count (get-in broker-state [topic partition :messages]))]
    (case (config "auto.offset.reset")
      "earliest" 0
      "latest" latest-offset
      "none" (throw (UnsupportedOperationException.))
      latest-offset)))

;; TODO: implement missing methods
;; TODO: validate config?
;; TODO: instead of registered topics, it may be easier to have topicpartitions for pause
(defrecord MockConsumer [consumer-state config]
  Consumer
  (assign [_ partitions])
  (close [_] (swap! consumer-state close-mock))
  (commitAsync [_])
  (commitAsync [_ offsets cb])
  (commitAsync [_ cb])
  (commitSync [_])
  (commitSync [_ offsets])
  (committed [_ partition])
  (listTopics [_])
  (metrics [_] (throw (UnsupportedOperationException.)))
  (partitionsFor [_ topic])
  (pause [_ partitions])
  (paused [_])
  (poll [this max-timeout]
    ;; TODO: on timeout is it empty ConsumerRecords or nil? assuming nil for now
    ;; TODO: what does kafka do if not subscribed to any topics? currently assuming nil
    ;; TODO: round robin across topic-partitions?
    ;; TODO: wakeup when trying to get more messages
    ;; TODO: throw wakeup exception if already had been woken up before
    ;; TODO: use consumer committed offset
    ;; TODO: assert not closed
    (let [state @broker-state
          {:keys [subscribed-topic-partitions wakeup-chan woken-up?]} @consumer-state
          poll-chan (chan buffer-size)]
      (if woken-up?
        (throw (WakeupException.))
        (do
          ;; Tell broker we'll be ready when it gets more messages
          (doseq [[topic-partition _] subscribed-topic-partitions]
            (>!! (get-in state [(.topic topic-partition) (.partition topic-partition) :watchers]) poll-chan))
          (let [messages (mapcat (fn [[topic-partition read-offset]]
                                   (let [topic (.topic topic-partition)
                                         partition (.partition topic-partition)
                                         messages (get-in state [topic partition :messages])]
                                     (when (< read-offset (count messages))
                                       (subvec messages read-offset))))
                                 subscribed-topic-partitions)
                read-messages (take (max-poll-records config) messages)
                grouped-messages (group-by record->topic-partition read-messages)
                new-read-offsets (read-offsets grouped-messages)]
            (if (> (count read-messages) 0)
              (do
                (swap! consumer-state update :subscribed-topic-partitions merge new-read-offsets)
                (ConsumerRecords. grouped-messages))
              ;; Maybe we didn't actually have any messages to read
              (alt!!
                ;; Broker got new messsages on some topic+partition that this
                ;; consumer is interested in. It is possible through race conditions
                ;; that this signal was a lie, that is, that we already read the
                ;; messages the broker is trying to tell us about, but it is
                ;; harmless to retry.
                poll-chan ([_] (println "poll") (.poll this max-timeout))
                ;; Or if we've waited too long for messages, give up
                (timeout max-timeout) ([_] (println "timing out") nil)
                wakeup-chan ([_] (throw (WakeupException.))))))))))
  (position [_ partition])
  (resume [_ partitions])
  (seek [_ partition offset])
  (seekToBeginning [_ partitions])
  (seekToEnd [_ partitions])
  (subscribe [_ topics]
    ;; TODO: what if already subscribed, what does Kafka do?
    (doseq [topic topics]
      (let [state-with-topic (swap! broker-state ensure-topic topic)
            partition-count (count (state-with-topic topic))]
        (swap! consumer-state update :subscribed-topic-partitions into (for [i (range partition-count)]
                                                                         [(->topic-partition topic i)
                                                                          (get-offset state-with-topic topic i config)])))))
  (unsubscribe [_]
    (swap! consumer-state assoc :subscribed-topic-partitions {}))
  (wakeup [_]
    (swap! consumer-state assoc :woken-up? true)
    (close! (:wakeup-chan @consumer-state))))

(defn mock-consumer
  ([config] (mock-consumer [] config))
  ([auto-subscribe-topics config]
   (let [mock-consumer (->MockConsumer (atom {:subscribed-topic-partitions {}
                                              :wakeup-chan (chan)})
                                       (or config {}))]
     (when (seq auto-subscribe-topics)
       (.subscribe mock-consumer auto-subscribe-topics))
     mock-consumer)))

(defn mock-consumer-task [{:keys [config logger exception-handler consumer-component]} task-id]
  (core/->ConsumerAlwaysCommitTask logger exception-handler (:consumer consumer-component)
                                   (config :kafka-consumer-config) (partial mock-consumer (config :topics-or-regex))
                                   (atom nil) task-id))

(defn mock-consumer-pool
  ([config]
   (core/map->KafkaConsumerPool {:config config
                                 :make-consumer-task mock-consumer-task}))
  ([config consumer-component logger exception-handler]
   (core/->KafkaConsumerPool config consumer-component logger exception-handler mock-consumer-task)))

;; TODO: assertions
(defn assert-proper-config [config])
(defn assert-proper-record [record])
(defn assert-producer-not-closed [producer-state])

(defn producer-record->consumer-record [offset record]
  (ConsumerRecord. (.topic record) (or (.partition record) 0) offset (.key record) (.value record)))

(defn add-record-in-broker-state [state consumer-record]
  (let [topic (.topic consumer-record)]
    (-> state
        (ensure-topic topic)
        (update-in [topic (.partition consumer-record) :messages] conj consumer-record))))

(defn drain [ch]
  ;; TODO: can infinite loop in certain race conditions
  (loop [out []]
    (if-let [o (poll! ch)]
      (recur (conj out o))
      out)))

(defn save-record! [record]
  (locking broker-lock
    (let [topic (.topic record)
          offset (count (get-in @broker-state [topic (or (.partition record) 0) :messages]))
          consumer-record (producer-record->consumer-record offset record)
          state-with-record (swap! broker-state add-record-in-broker-state consumer-record)]
      (let [ch (get-in @broker-state [topic (.partition consumer-record) :watchers])]
        (doseq [ch (drain ch)]
          (close! ch)))
      consumer-record)))

(def noop-cb
  (reify
    Callback
    (onCompletion [this record-metadata e])))

(defn committed-record-metadata [record]
  (RecordMetadata. (record->topic-partition record) 0 (.offset record)
                   (.timestamp record) (.checksum record)
                   (.serializedKeySize record) (.serializedValueSize record)))


(defrecord MockProducer [producer-state config]
  Producer
  (close [_] (swap! producer-state close-mock))
  (close [_ timeout time-unit] (swap! producer-state close-mock))
  (flush [_])
  (metrics [_] (throw (UnsupportedOperationException.)))
  (partitionsFor [_ topic] (throw (UnsupportedOperationException.)))
  (send [this record]
    (.send this record noop-cb))
  (send [_ producer-record cb]
    (assert-proper-config config)
    (assert-proper-record producer-record)
    (assert-producer-not-closed producer-state)
    (let [consumer-record (save-record! producer-record)
          record-metadata (committed-record-metadata consumer-record)]
      (.onCompletion cb record-metadata nil)
      (future record-metadata))))

(defn mock-producer [config]
  (->MockProducer (atom nil) config))

(defn mock-producer-component [config]
  (core/->KafkaProducerComponent config mock-producer))