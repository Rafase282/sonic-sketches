(ns sonic-sketches.core
  (:use [overtone.live])
  (:require [overtone.inst.drum :as drums]
            [overtone.inst.synth :refer [tb303 overpad]]
            [clojure.core.async :as async]
            [clojure.data.generators :as datagen]
            [amazonica.aws.s3 :as s3])
  (:gen-class))

(defn clock-signal
  "Given a metronome, returns a channel that is fed on every beat."
  [nome]
  (let [out (async/chan)]
    (async/go-loop [tick (metro-tick nome)]
      (when-let [pulse (async/>! out nome)]
        (do
          (async/<! (async/timeout tick))
          (recur (metro-tick nome)))))
    out))

(defn play-sequence
  "Abstracting the notion of playing through a sequence, this fn takes
  a clock, an input channel, and a lambda. It takes
  input off the channel at every clock tick and calls the lambda with
  the input if pred returns true for that step. Returns an async
  channel that will block until the input is closed and returns the
  metronome powering the clock. e.g.

    (async/<!! (play-sequence (clock-pulse (metronome 96))
                              (async/to-chan (range 16))
                              (fn [x] (drums/kick))
                              (constantly true)))

  performs a blocking take until 16 beats have elapsed."
  ([clock in f]
   (play-sequence clock in f (constantly true)))

  ([clock in f pred]
   (async/go-loop []
     (when-let [pulse (async/<! clock)]
       (if-let [step (async/<! in)]
         (do
           (when (pred step) (f step))
           (recur))
         pulse)))))

(defn step-sequencer
  "Accepts a metronome, an instrument, and a seq of 0's or 1's. If the
  pulse is 1, the instrument will play. The sequence will be treated
  as if each step is a fraction of a beat, the denominator being the
  number of beats in a bar as determined by the metronome."
  [nome instrument pulses]
  (let [bpb (metro-bpb nome)
        bpm (metro-bpm nome)
        rate (* bpm bpb)]
    (play-sequence (clock-signal (metronome rate))
                   (async/to-chan pulses)
                   (fn [x] (instrument))
                   pos?)))

(defn note-sequencer
  "Similar to the step-sequencer, except the sequence should be a
  vector of key, value pairs to be applied to the instrument"
  [nome instrument pulses]
  (let [bpb (metro-bpb nome)
        bpm (metro-bpm nome)
        rate (* bpm bpb)]
    (play-sequence (clock-signal (metronome rate))
                   (async/to-chan pulses)
                   #(apply instrument %)
                   some?)))

(defn drummachine
  "Accepts a metronome and a vector of instructions. Each instruction
  is a pair of instruments and a sequence of 0's or 1's. Returns a seq
  of async channels suitable for use with `alts!!`"
  [nome instructions]
  (for [[instrument pulses] instructions]
    (async/go (async/<! (step-sequencer nome instrument pulses)))))

(defn rand-notesequence
  "Produce a n notes from given scale randomly. Returns a vector
  suitable for application to a tb303."
  [n scale]
  (mapv (partial vector :note) (repeatedly n #(datagen/rand-nth scale))))

(defn rand-drumsequence
  "Randomly generates a drum sequence for each percussion
  instrument. Accepts a vector of instruments and the number of steps
  to generate. Defaults to 16 steps."
  ([percussion] (rand-drumsequence percussion 16))
  ([percussion nsteps]
   (->> #(datagen/rand-nth [0 1])
        repeatedly
        (partition nsteps)
        (mapv vector percussion))))

(defn loop-sequence
  "Loop each drum in a drumsequence n times."
  [drumsequences n]
  (for [drum drumsequences]
    (update-in drum [1] #(flatten (repeat n %)))))

(defn cycle-sequence
  "Loop the drum pattern sequence infinitely."
  [drumsequences]
  (for [drum drumsequences]
    (update-in drum [1] cycle)))

(def four-on-the-floor
  [[drums/kick       [1 0 0 0 1 0 0 0 1 0 0 0 1 0 0 0]]
   [drums/clap       [0 0 0 0 1 0 0 0 0 0 0 0 1 0 0 0]]
   [drums/closed-hat [0 0 1 0 0 0 1 0 0 0 1 0 0 0 1 0]]])

(def tempo-map
  "The values represent ranges of BPM. See:
  https://en.wikipedia.org/wiki/Tempo#Italian_tempo_markings"
  {:adagio   (range 66 76)
   :andante  (range 76 108)
   :moderato (range 108 120)
   :allegro  (range 120 168)
   :vivace   (range 168 176)
   :presto   (range 168 200)})

(def percussion
  "A list of all possible drums"
  (->> 'drums
       (get (ns-aliases *ns*))
       ns-publics
       vals
       (map var-get)))

(defn rand-metronome
  "Given a tempo, choose a random BPM."
  [tempo]
  (->> (tempo tempo-map)
       datagen/rand-nth
       metronome))

(defmacro make-recording
  [path out]
  `(do
     (println "🎼 Recording to" ~path "now.")
     (recording-start ~path)
     (async/<!! ~out)
     (let [recorded# (recording-stop)]
       (println "Finished recording to" recorded# "🎶")
       recorded#)))

(defn upload-to-s3
  "Upload a file at path to s3"
  [path & metadata]
  (let [credentials {:profile "sonic-sketch"}
        recording (java.io.File. path)
        key-name (.getName recording)]
    (println "Uploading" key-name "to S3")
    (s3/put-object credentials
                   :bucket-name "sonic-sketches"
                   :key key-name
                   :file recording
                   :metadata {:user-metadata (apply hash-map metadata)})))

(defn -main
  [& args]
  (let [tempfile (java.io.File/createTempFile "test" ".wav")
        path (.getPath tempfile)
        seed (now)]
    (binding [datagen/*rnd* (java.util.Random. seed)]
      (let [drums (datagen/reservoir-sample 6 percussion) ; 6 seems like a good number
            nome (rand-metronome :andante)
            drumsequence (-> (rand-drumsequence drums)
                             (loop-sequence 8))]
        (println "🎲 RNG Seed:" seed)
        (-> (make-recording path
                            (async/go (async/alts! (drummachine nome drumsequence))))
            (upload-to-s3 :rng-seed seed
                          :bpm (metro-bpm nome)))))))
