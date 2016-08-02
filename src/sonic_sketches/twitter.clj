(ns sonic-sketches.twitter
  "Utilities for uploading a song to Twitter"
  (:use [clojure.java.shell :only [sh]]
        [clojure.string :only [capitalize]])
  (:require [clj-http.client :as http]
            [clojure.data.json :as json]
            [oauth.client :as oauth]
            [clj-http.client :as http]
            [clojure.data.json :as json]
            [environ.core :refer [env]])
  (:import [twitter4j TwitterFactory StatusUpdate GeoLocation]))

(defonce client (TwitterFactory/getSingleton))

(defonce consumer (oauth/make-consumer (env :twitter4j-oauth-consumerkey)
                                       (env :twitter4j-oauth-consumersecret)
                                       "https://api.twitter.com/oauth/request_token"
                                       "https://api.twitter.com/oauth/access_token"
                                       "https://api.twitter.com/oauth/authorize"
                                       :hmac-sha1))

(defn oauth-post
  [params & more]
  (let [uri "https://upload.twitter.com/1.1/media/upload.json"
        credentials (oauth/credentials consumer
                                       (env :twitter4j-oauth-accesstoken)
                                       (env :twitter4j-oauth-accesstokensecret)
                                       :POST
                                       uri
                                       params)]
    (http/post uri (apply merge {:query-params (merge credentials params)} more))))

(defn wav->mp4
  "Convert a path to a wav to an mp4. Returns a file object to the mp4."
  [path]
  (let [wav (java.io.File. path)
        dir (.getParent wav)
        filename (clojure.string/replace (.getName wav) #"\.wav" ".mp4")
        mp4 (java.io.File. dir filename)
        ffmpeg (sh "ffmpeg" "-y" "-i" path
                   "-filter_complex"
                   "[0:a]showwaves=s=320x180:mode=line,format=yuv420p[v]"
                   "-map" "[v]" "-map" "0:a" "-b:a" "64k" "-b:v" "256k"
                   (.getPath mp4))]
    (if (zero? (:exit ffmpeg))
      mp4
      (throw (Exception. (str "Error converting wav" (:err ffmpeg)))))))

(defn upload-media
  "Twitter4j doesn't support the chunked upload required for uploading
  video to Twitter, so this fn handles that. Don't try to upload
  something larger than 5M.

  See https://dev.twitter.com/rest/media/uploading-media#chunkedupload"
  [file]
  (let [bytes (.length file)
        init (oauth-post {:command "INIT" :media_type "video/mp4" :total_bytes bytes})
        media-id (-> (:body init)
                     (json/read-str :key-fn keyword)
                     :media_id)]
    (oauth-post {:command "APPEND" :media_id media-id :segment_index 0}
                {:multipart [{:name "media" :content file}]})
    (-> (oauth-post {:command "FINALIZE" :media_id media-id})
        :body
        (json/read-str :key-fn keyword))))

(defn mkstatus
  [songdata]
  (let [{:keys [day-of-week iso8601 lunar-phase avg-temp precipitation]} songdata
        day (capitalize day-of-week)
        message (format "It is %s." day)]
    (str message "\n\n" lunar-phase))) ;; TODO: Convert to emoji

(defn tweet
  "Tweet the song from a path"
  [path metadata]
  (let [{:keys [latitude longitude]} metadata
        statusmsg (mkstatus metadata)
        geo (GeoLocation. latitude longitude)
        mp4 (wav->mp4 path)
        media-id (:media_id (upload-media mp4))
        media-id-array (into-array Long/TYPE [media-id])
        status (doto (StatusUpdate. statusmsg)
                 (.setLocation geo)
                 (.setMediaIds media-id-array))]
    (.updateStatus client status)))
