(ns crux.cli
  (:require [clojure.edn :as edn]
            [clojure.pprint :as pp]
            [clojure.tools.cli :as cli]
            [clojure.tools.logging :as log]
            [crux.node :as n]
            [crux.config :as cc]
            [crux.io :as cio]
            [clojure.java.io :as io])
  (:import (java.io Closeable File)))

(def default-options
  {:crux.node/topology '[crux.standalone/topology crux.http-server/module]
   :crux.kv/db-dir "db-dir"
   :crux.standalone/event-log-dir "event-log"
   :crux.standalone/kv-store 'crux.kv.memdb/kv
   :crux.standalone/event-log-kv-store 'crux.kv.memdb/kv})

(def cli-options
  [["-e" "--edn-file EDN_FILE" "EDN file to load Crux options from"
    :parse-fn io/file
    :validate [#(.exists ^File %) "EDN file doesn't exist"]]

   ["-p" "--properties-file PROPERTIES_FILE" "Properties file to load Crux options from"
    :parse-fn io/file
    :parse-fn [#(.exists ^File %) "Properties file doesn't exist"]]

   ["-x" "--extra-edn-options EDN_OPTIONS" "Extra options as an quoted EDN map."
    :default nil
    :parse-fn edn/read-string]

   ["-h" "--help"]])

;; NOTE: This isn't registered until the node manages to start up
;; cleanly, so ctrl-c keeps working as expected in case the node
;; fails to start.
(defn- shutdown-hook-promise []
  (let [main-thread (Thread/currentThread)
        shutdown? (promise)]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. (fn []
                                 (let [shutdown-ms 10000]
                                   (deliver shutdown? true)
                                   (shutdown-agents)
                                   (.join main-thread shutdown-ms)
                                   (when (.isAlive main-thread)
                                     (log/warn "could not stop node cleanly after" shutdown-ms "ms, forcing exit")
                                     (.halt (Runtime/getRuntime) 1))))
                               "crux.shutdown-hook-thread"))
    shutdown?))

(defn- options->table [options]
  (with-out-str
    (pp/print-table (for [[k v] options]
                      {:key k :value v}))))

(defn- if-it-exists [^File f]
  (when (.exists f)
    f))

(defn merge-options [{:keys [edn-file properties-file extra-edn-options]}]
  (merge default-options
         (some-> (or properties-file
                     (-> (io/file "crux.properties") if-it-exists)
                     (io/resource "crux.properties"))
                 (cc/load-properties))
         (some-> (or edn-file
                     (-> (io/file "crux.edn") if-it-exists)
                     (io/resource "crux.edn"))
                 (slurp)
                 (edn/read-string))
         extra-edn-options))

(defn start-node-from-command-line [args]
  (cio/install-uncaught-exception-handler!)

  (let [{:keys [options errors summary]} (-> (cli/parse-opts args cli-options)
                                             (update :options merge-options))

        {:keys [version revision]} n/crux-version]
    (cond
      (:help options)
      (println summary)

      errors
      (binding [*out* *err*]
        (doseq [error errors]
          (println error))
        (System/exit 1))

      :else
      (do (log/infof "Crux version: %s revision: %s" version revision)
          (log/info "options:" (options->table options))
          (with-open [node (n/start options)]
            @(shutdown-hook-promise))))))
