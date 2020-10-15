(ns edd.postgres.event-store
  (:require [clojure.tools.logging :as log]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [lambda.uuid :as uuid]
            [clojure.string :as str]
            [edd.dal :refer [get-events
                             get-max-event-seq
                             get-sequence-number-for-id
                             get-id-for-sequence-number
                             get-aggregate-id-by-identity
                             log-dps
                             log-request
                             log-response
                             store-results]]
            [next.jdbc.prepare :as p]
            [edd.db :as db]
            [lambda.util :as util]
            [lambda.elastic :as elastic]))

(def errors
  {:unique "duplicate key value violates unique constraint"})

(defn try-to-data
  [func]
  (try
    (func)
    (catch Exception e
      (log/error e "Postgres error")
      {:error (-> (.getMessage e)
                  (str/replace "\n" ""))})))



(defn store-event
  [ctx event]
  (log/debug "Storing event" event)
  (when event
    (jdbc/execute! (:con ctx)
                   ["INSERT INTO glms.event_store(id, service, request_id, interaction_id, event_seq, aggregate_id, data)
                            VALUES (?,?,?,?,?,?,?)"
                    (uuid/gen)
                    (:service-name ctx)
                    (:request-id ctx)
                    (:interaction-id ctx)
                    (:event-seq event)
                    (:id event)
                    event])))

(defn store-cmd
  [ctx cmd]
  (log/debug "Storing command" cmd)
  (when cmd
    (jdbc/execute! (:con ctx)
                   ["INSERT INTO glms.command_store(id, service, data)
                            VALUES (?,?,?)"
                    (uuid/gen)
                    (name (:service cmd))
                    cmd])))

(defmethod log-request
  :postgres
  [{:keys [body request-id interaction-id service-name] :as ctx}]
  (log/debug "Storing request" (:commands body))
  (when (:commands body)
    (let [ps (jdbc/prepare (:con ctx)
                           ["INSERT INTO glms.command_request_log(request_id,
                                                          interaction_id,
                                                          service_name,
                                                          cmd_index,
                                                          data)
                            VALUES (?,?,?,?,?)"])
          params (map-indexed
                   (fn [idx itm] [request-id
                                  interaction-id
                                  service-name
                                  idx
                                  itm])
                   (:commands body))]
      (p/execute-batch! ps params))))

(defmethod log-dps
  :postgres
  [{:keys [dps-resolved request-id interaction-id service-name] :as ctx}]
  (log/debug "Storing deps" dps-resolved)
  (when dps-resolved
    (let [ps (jdbc/prepare (:con ctx)
                           ["INSERT INTO glms.command_deps_log(request_id,
                                                          interaction_id,
                                                          service_name,
                                                          cmd_index,
                                                          data)
                            VALUES (?,?,?,?,?)"])
          params (map-indexed
                   (fn [idx itm] [request-id
                                  interaction-id
                                  service-name
                                  idx
                                  itm])
                   dps-resolved)]
      (p/execute-batch! ps params))
    ctx))

(defmethod log-response
  :postgres
  [{:keys [resp request-id interaction-id service-name] :as ctx}]
  (log/debug "Storing response" resp)
  (when resp
    (jdbc/execute! (:con ctx)
                   ["INSERT INTO glms.command_response_log(request_id,
                                                           interaction_id,
                                                           service_name,
                                                           data)
                            VALUES (?,?,?,?)"
                    request-id
                    interaction-id
                    service-name
                    resp])))

(defn store-identity
  [ctx identity]
  (log/debug "Storing identity" identity)
  (jdbc/execute! (:con ctx)
                 ["INSERT INTO glms.identity_store(id, service, aggregate_id)
                            VALUES (?,?,?)"
                  (:identity identity)
                  (name (:service-name ctx))
                  (:id identity)]))

(defn store-sequence
  [ctx sequence]
  {:pre [(:id sequence)]}
  (log/debug "Storing sequence" sequence)
  (let [service-name (:service-name ctx)
        aggregate-id (:id sequence)]
    (jdbc/execute! (:con ctx)
                   ["BEGIN WORK;
                        LOCK TABLE glms.sequence_store IN EXCLUSIVE MODE;
                       INSERT INTO glms.sequence_store (aggregate_id, service_name, value)
                            VALUES (?, ?, (SELECT COALESCE(MAX(value), 0) +1
                                             FROM glms.sequence_store
                                            WHERE service_name = ?));
                    COMMIT WORK;"
                    aggregate-id
                    service-name
                    service-name])))

(defmethod get-sequence-number-for-id
  :postgres
  [ctx query]
  {:pre [(:id query)]}
  (let [service-name (:service-name ctx)
        result (jdbc/execute-one!
                 (:con ctx)
                 ["SELECT value
                     FROM glms.sequence_store
                    WHERE aggregate_id = ?
                      AND service_name = ?"
                  (:id query)
                  service-name]
                 {:builder-fn rs/as-unqualified-lower-maps})]
    (:value result)))

(defmethod get-id-for-sequence-number
  :postgres
  [{:keys [sequence] :as ctx}]
  {:pre [sequence]}
  (let [service-name (:service-name ctx)
        result (jdbc/execute-one!
                 (:con ctx)
                 ["SELECT aggregate_id
                     FROM glms.sequence_store
                    WHERE value = ?
                      AND service_name = ?"
                  sequence
                  service-name]
                 {:builder-fn rs/as-unqualified-lower-maps})]
    (:aggregate_id result)))

(defmethod get-aggregate-id-by-identity
  :potgres
  [ctx identity]
  (let [result
        (jdbc/execute-one! (:con ctx)
                           ["SELECT aggregate_id FROM glms.identity_store
                                           WHERE id = ?
                                           AND service = ?"
                            identity
                            (:service-name ctx)]
                           {:builder-fn rs/as-unqualified-lower-maps})]
    (log/debug "Query result" result)
    (:aggregate_id result)))

(defmethod get-events
  :postgres
  [{:keys [id] :as ctx}]
  (log/debug "Fetching events for aggregate" id)
  (let [data (try-to-data
               #(jdbc/execute! (:con ctx)
                               ["SELECT data
                         FROM glms.event_store
                        WHERE aggregate_id=?
                     ORDER BY event_seq ASC" id]
                               {:builder-fn rs/as-arrays}))]
    (if (:error data)
      data
      (flatten
        (rest
          data)))))

(defmethod get-max-event-seq
  :postgres
  [ctx id]
  (log/debug "Fetching max event-seq for aggregate" id)
  (:max
    (jdbc/execute-one! (:con ctx)
                       ["SELECT COALESCE(MAX(event_seq), 0) AS max
                         FROM glms.event_store WHERE aggregate_id=?" id]
                       {:builder-fn rs/as-unqualified-lower-maps})))

(defn store-events
  [{:keys [events] :as ctx}]
  (log/debug "Storing events")
  (doall
    (for [event (flatten events)]
      (store-event ctx event)))
  events)

(defn store-identities
  [ctx identities]
  (log/debug "Storing identities" identities)
  (doall
    (for [ident identities]
      (store-identity ctx ident)))
  identities)

(defn store-sequences
  [ctx sequences]
  (log/debug "Storing sequences" sequences)
  (doall
    (for [sequence sequences]
      (store-sequence ctx sequence)))
  sequences)



(defn store-commands
  [ctx commands]
  (log/debug "Storing effects" commands)
  (doall
    (for [cmd commands]
      (store-cmd ctx (assoc
                       cmd
                       :request-id (:request-id ctx)
                       :interaction-id (:interaction-id ctx)))))
  commands)

(defmethod store-results
  :postgres
  [ctx func]
  (jdbc/with-transaction
    [tx (:con ctx)]
    (try-to-data
      #(func
         (assoc ctx :con tx)))))


(defn register
  [ctx]
  (assoc ctx :event-store :postgres))