(ns edd.memory.view-store
  (:require [clojure.data :refer [diff]]
            [clojure.tools.logging :as log]
            [lambda.test.fixture.state :refer [*dal-state*]]
            [edd.memory.search :refer [advanced-search-impl]]
            [lambda.test.fixture.state :refer [*dal-state*]]
            [edd.search :refer [with-init
                                simple-search
                                advanced-search
                                update-aggregate]]))

(defn filter-aggregate
  [query aggregate]
  (let [res (diff aggregate query)]
    (and (= (second res) nil)
         (= (nth res 2) query))))

(defmethod simple-search
  :memory
  [{:keys [query]}]
  {:pre [query]}
  (into []
        (filter
          #(filter-aggregate
             (dissoc query :query-id)
             %)
          (->> @*dal-state*
               (:aggregate-store)))))

(defn update-aggregate-impl
  [{:keys [aggregate] :as ctx}]
  (log/info "Emulated 'update-aggregate' dal function")
  (swap! *dal-state*
         #(update % :aggregate-store
                  (fn [v]
                    (conj (filter
                            (fn [el]
                              (not= (:id el) (:id aggregate)))
                            v)
                          aggregate))))
  ctx)

(defmethod update-aggregate
  :memory
  [ctx]
  (update-aggregate-impl ctx))

(defmethod advanced-search
  :memory
  [ctx]
  (advanced-search-impl ctx))

(defmethod with-init
  :memory
  [ctx body-fn]
  (log/debug "Initializing memory view store")
  (if (bound? #'*dal-state*)
    (body-fn ctx)
    (binding [*dal-state* (atom {})]
      (body-fn ctx))))

(defn register
  [ctx]
  (assoc ctx :view-store :memory))
