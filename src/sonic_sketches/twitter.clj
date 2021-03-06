(ns sonic-sketches.twitter
  "Utilities for uploading a song to Twitter"
  (:use [clojure.java.shell :only [sh]]
        [clojure.string :only [capitalize]]
        [sonic-sketches.emoji])
  (:require [clj-http.client :as http]
            [clojure.data.json :as json]
            [oauth.client :as oauth]
            [environ.core :refer [env]]
            [clojure.data.generators :as datagen])
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

(def ffmpeg-colors
  "A list of ffmpeg colors. From:
    ffmpeg -colors 2>/dev/null | tail -n +2 | awk '{print $1}'"
  (map str '(AliceBlue AntiqueWhite Aqua Aquamarine Azure
             Beige Bisque Black BlanchedAlmond Blue
             BlueViolet Brown BurlyWood CadetBlue Chartreuse
             Chocolate Coral CornflowerBlue Cornsilk Crimson
             Cyan DarkBlue DarkCyan DarkGoldenRod DarkGray
             DarkGreen DarkKhaki DarkMagenta DarkOliveGreen Darkorange
             DarkOrchid DarkRed DarkSalmon DarkSeaGreen DarkSlateBlue
             DarkSlateGray DarkTurquoise DarkViolet DeepPink DeepSkyBlue
             DimGray DodgerBlue FireBrick FloralWhite ForestGreen
             Fuchsia Gainsboro GhostWhite Gold GoldenRod
             Gray Green GreenYellow HoneyDew HotPink
             IndianRed Indigo Ivory Khaki Lavender
             LavenderBlush LawnGreen LemonChiffon LightBlue LightCoral
             LightCyan LightGoldenRodYellow LightGreen LightGrey LightPink
             LightSalmon LightSeaGreen LightSkyBlue LightSlateGray LightSteelBlue
             LightYellow Lime LimeGreen Linen Magenta
             Maroon MediumAquaMarine MediumBlue MediumOrchid MediumPurple
             MediumSeaGreen MediumSlateBlue MediumSpringGreen MediumTurquoise MediumVioletRed
             MidnightBlue MintCream MistyRose Moccasin NavajoWhite
             Navy OldLace Olive OliveDrab Orange
             OrangeRed Orchid PaleGoldenRod PaleGreen PaleTurquoise
             PaleVioletRed PapayaWhip PeachPuff Peru Pink
             Plum PowderBlue Purple Red RosyBrown
             RoyalBlue SaddleBrown Salmon SandyBrown SeaGreen
             SeaShell Sienna Silver SkyBlue SlateBlue
             SlateGray Snow SpringGreen SteelBlue Tan
             Teal Thistle Tomato Turquoise Violet
             Wheat White WhiteSmoke Yellow YellowGreen)))

(defn wav->mp4
  "Convert a path to a wav to an mp4. Returns a file object to the mp4."
  [path seed]
  (let [wav (java.io.File. path)
        dir (.getParent wav)
        filename (clojure.string/replace (.getName wav) #"\.wav" ".mp4")
        mp4 (java.io.File. dir filename)
        [wavecolor lifecolor deathcolor] (datagen/reservoir-sample 3 ffmpeg-colors)
        filter (str "[0:a]showwaves=s=640x360:r=20:mode=cline:colors=" wavecolor "[fg];"
                    "life=s=640x360:ratio=0.1:mold=2:life_color=" lifecolor ":death_color=" deathcolor ":"
                    "seed=" (Math/abs (.intValue seed)) "[bg];"
                    "[bg][fg]overlay=shortest=1,format=yuv420p[v]")
        ffmpeg (sh "ffmpeg" "-y" "-i" path
                   "-filter_complex"
                   filter
                   "-map" "[v]" "-map" "0:a" "-b:a" "64k" "-b:v" "768k"
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
  (let [{:keys [day-of-week iso8601 lunar-phase avg-temp precipitation interval]} songdata
        day (capitalize day-of-week)
        greetings [(format "It is %s." day)
                   (format "It's %s." day)
                   (format "Today is %s." day)
                   (format "Good morning. It's %s." day)
                   (format "Happy %s." day)
                   (format "Today is %s. Good morning." day)
                   (format "🎵 %s 🎶" day)]
        greeting (datagen/rand-nth greetings)
        follow-ups (cond (> avg-temp 80) ["It will be hot today."
                                          "Looks like it will be a hot one."
                                          "It's hot."
                                          ""]
                         (< avg-temp 30) ["It's cold out there."
                                          "Hace frio."
                                          "Brrr. It's chilly today."
                                          "⛄"
                                          ""]
                         (> precipitation 0.02) ["Consider an Umbrella."
                                                "Looks like rain."
                                                ""]
                         :else ["Have a nice day."
                                "Do your best."
                                ""])]
    (clojure.string/join " " [greeting
                              (datagen/rand-nth follow-ups)
                              "\n\n"
                              (lunar-str lunar-phase)
                              (precip-str-from-interval (keyword interval))
                              (temp-emoji avg-temp)])))

(defn tweet
  "Tweet the song from a path"
  [path metadata]
  (let [{:keys [latitude longitude rng-seed]} metadata]
    (binding [datagen/*rnd* (java.util.Random. rng-seed)]
      (let [statusmsg (mkstatus metadata)
            geo (GeoLocation. latitude longitude)
            mp4 (wav->mp4 path rng-seed)
            media-id (:media_id (upload-media mp4))
            media-id-array (into-array Long/TYPE [media-id])
            status (doto (StatusUpdate. statusmsg)
                     (.setLocation geo)
                     (.setMediaIds media-id-array))]
        (.updateStatus client status)))))
