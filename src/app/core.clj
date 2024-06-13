(ns app.core
  (:require [ring.adapter.jetty :refer [run-jetty]]
            [ring.middleware.multipart-params :refer [wrap-multipart-params]]
            [muuntaja.middleware :as middleware]
            [compojure.core :refer [GET POST context routes]]
            [cheshire.core :as json]
            [babashka.process :refer [shell]]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.data.codec.base64 :as b64])
  (:import (java.util UUID Date)))

;; The purpose of this API is that the browser extensions and importers can
;; insert new chunks.

(def ^{:private true} files-directory "files")
(defn- uuid [] (.toString (UUID/randomUUID)))

(defn- without-data-uri-prefix
  [data]
  (str/replace-first data #"data\:image\/png\;base64\," ""))

(defn- stream->disk [stream file-name]
  (with-open [xout (io/output-stream file-name)]
    (io/copy stream xout)))

(defn- save-html! [chunk-id data]
  (spit (format "%s/%s.html" files-directory chunk-id)
    data))

(defn- save-image! [chunk-id data]
  (stream->disk
    (b64/decode (.getBytes (without-data-uri-prefix data)))
    (format "%s/%s.png" files-directory chunk-id)))

(defn- save-chunk! [chunk-id data]
  (spit (format "%s/%s.edn" files-directory chunk-id)
    (pr-str data)))

(defn upload-handler [request]
  (try
    (let [chunk-id (uuid)
          _ (println "ID:" chunk-id)
          tab-info (-> (:params request)
                     (get "tab_info")
                     (json/parse-string true))
          url (:url tab-info)]
      (save-html! chunk-id (get (:params request) "html"))
      (save-image! chunk-id (get (:params request) "img"))
      (save-chunk! chunk-id (assoc tab-info :captured-at (Date.)))
      (println "ID:" chunk-id "- Successfully saved URL:" url)
      #_(shell (format "osascript -e 'display notification \"saved %s\" with title \"bitfondue local API\"'"
               url))
      {:status 200
       :body "thank you"})
    (catch Exception _ {:status 500
                        :body "An error occurred while processing your upload"})))

(defn root-handler [_]
  {:status 200
   :body (str "Hello there, this is a local endpoint to allow for storing websites to disk.")})

(defn app
  []
  (-> (routes
        (GET "/" [] root-handler)
        (context "/upload" []
          (POST "/" request
            ((-> upload-handler
               middleware/wrap-format
               wrap-multipart-params) request))))))


(defonce server (atom nil))
(defn -main [& _]
  (if (nil? @server)
    (do (reset! server (run-jetty
                         (app)
                         {:port 8085
                          :join? false}))
        (println "Local API server started and ready to receive requests."))
    (println "Server already started and won't be started again.")))

(comment
  ;; can I read the .edn file?
  (->> (slurp "files/84b96534-bbfb-4d8d-be48-e5746845f332.edn")
    (edn/read-string)
    :captured-at
    type)

  (-main)

  )
