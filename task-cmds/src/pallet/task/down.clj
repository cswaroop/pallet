(ns pallet.task.down
  "Remove project nodes."
  (:require
   [clojure.pprint :refer [print-table]]
   [clojure.stacktrace :refer [print-cause-trace]]
   [pallet.api :as api]
   [pallet.plan :refer [phase-errors]]
   [pallet.task-utils :refer [pallet-project process-args project-groups]]
   [pallet.utils :refer [apply-map]]))

(def switches
  [["-s" "--selectors" "A comma separated list of selectors"
    :default "default"]
   ["-g" "--groups" "A comma separated list of groups"]
   ["-r" "--roles" "A comma separated list of group roles"]
   ["-q" "--quiet" "No output on successful completion"
    :flag true :default false]])

(def help
  (str "Bring down project nodes.\n"
       \newline
       "If you have variants defined, you can use `-s` to select variants.\n"
       \newline
       (last (process-args "nodes" nil switches))))

(defn ^{:doc help} down
  [{:keys [compute project] :as request} & args]
  (let [[{:keys [selectors quiet groups roles]} args]
        (process-args "down" args switches)
        spec (project-groups
              (pallet-project project) compute selectors groups roles)]
    (let [op (apply-map api/converge
                        (map #(assoc % :count 0) spec)
                        :async true
                        (->
                         request
                         (dissoc :config :project)
                         (assoc :environment
                           (or (:environment request)
                               (-> request :project :environment)))))
          result (deref op (* 30 60 1000) nil)]
      (if (or (nil? result) (phase-errors result) (:exception result))
        (binding [*out* *err*]
          (println "An error occured")
          (when-let [e (seq (phase-errors result))]
            (print-table (->> e (map :error) (map #(dissoc % :type)))))
          (when-let [e (:exception result)]
            (print-cause-trace e)
            (throw (ex-info "pallet down failed" {:exit-code 1} e)))
          (throw (ex-info "See logs for further details" {:exit-code 1})))
        (when-not quiet
          (println "Removed" (count (:old-nodes result)) "nodes"))))))
