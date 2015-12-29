(ns sonic-sketches.core
  (:use [overtone.live])
  (:require [overtone.inst.synth])
  (:gen-class))

;; ======================================================================
;; Monotron Clone by Roger Allen.
;;   via some code in https://github.com/rogerallen/explore_overtone
;;
;; Source
;; http://korg.com/monotrons
;; http://korg.com/services/products/monotron/monotron_Block_diagram.jpg
;;
;; Following patterns from
;; https://github.com/overtone/overtone/blob/master/src/overtone/inst/synth.clj
;;
;; Inspiration
;; http://www.soundonsound.com/sos/aug10/articles/korg-monotron.htm
;; http://www.timstinchcombe.co.uk/index.php?pge=mono
;;
;; This was pulled from https://github.com/overtone/overtone/blob/master/src/overtone/examples/instruments/monotron.clj
(defsynth monotron
  "Korg Monotron from website diagram:
   http://korg.com/services/products/monotron/monotron_Block_diagram.jpg."
  [note     60            ; midi note value
   volume   0.7           ; gain of the output
   mod_pitch_not_cutoff 1 ; use 0 or 1 only to select LFO pitch or cutoff modification
   pitch    0.0           ; frequency of the VCO
   rate     4.0           ; frequency of the LFO
   int      1.0           ; intensity of the LFO
   cutoff   1000.0        ; cutoff frequency of the VCF
   peak     0.5           ; VCF peak control (resonance)
   pan      0             ; stereo panning
   ]
  (let [note_freq       (midicps note)
        pitch_mod_coef  mod_pitch_not_cutoff
        cutoff_mod_coef (- 1 mod_pitch_not_cutoff)
        LFO             (* int (saw rate))
        VCO             (saw (+ note_freq pitch (* pitch_mod_coef LFO)))
        vcf_freq        (+ cutoff (* cutoff_mod_coef LFO) note_freq)
        VCF             (moog-ff VCO vcf_freq peak)
        ]
    (out 0 (pan2 (* volume VCF) pan))))

(def jingle-bells
  [{:pitch (note :E4) :duration 1/4}
   {:pitch (note :E4) :duration 1/4}
   {:pitch (note :E4) :duration 1/2}

   {:pitch nil :duration 1/8}

   {:pitch (note :E4) :duration 1/4}
   {:pitch (note :E4) :duration 1/4}
   {:pitch (note :E4) :duration 1/2}

   {:pitch nil :duration 1/8}

   {:pitch (note :E4) :duration 1/4}
   {:pitch (note :G4) :duration 1/4}
   {:pitch (note :C4) :duration 1/4}
   {:pitch (note :D4) :duration 1/4}

   {:pitch (note :E4) :duration 1}

   {:pitch nil :duration 1/4}

   {:pitch (note :F4) :duration 1/4}
   {:pitch (note :F4) :duration 1/4}
   {:pitch (note :F4) :duration 1/4}
   {:pitch (note :F4) :duration 1/4}

   {:pitch (note :F4) :duration 1/4}
   {:pitch (note :E4) :duration 1/4}
   {:pitch (note :E4) :duration 1/2}

   {:pitch (note :E4) :duration 1/4}
   {:pitch (note :D4) :duration 1/4}
   {:pitch (note :D4) :duration 1/4}
   {:pitch (note :E4) :duration 1/4}

   {:pitch (note :D4) :duration 1/2}
   {:pitch (note :G4) :duration 1/2}
   ])

(def auld-lang-syne
  [{:pitch (note :C3) :duration 1/4}

   {:pitch (note :F3) :duration 3/8}
   {:pitch (note :F3) :duration 1/8}
   {:pitch (note :F3) :duration 1/4}
   {:pitch (note :A3) :duration 1/4}

   {:pitch (note :G3) :duration 3/8}
   {:pitch (note :F3) :duration 1/8}
   {:pitch (note :G3) :duration 1/4}
   {:pitch (note :A3) :duration 1/8}
   {:pitch (note :G3) :duration 1/8}

   {:pitch (note :F3) :duration 3/8}
   {:pitch (note :F3) :duration 1/8}
   {:pitch (note :A3) :duration 1/4}
   {:pitch (note :C4) :duration 1/4}
   {:pitch (note :D4) :duration 3/4}
   {:pitch (note :D4) :duration 1/4}

   {:pitch (note :C4) :duration 3/8}
   {:pitch (note :A3) :duration 1/8}
   {:pitch (note :A3) :duration 1/4}
   {:pitch (note :F3) :duration 1/4}

   {:pitch (note :G3) :duration 3/8}
   {:pitch (note :F3) :duration 1/8}
   {:pitch (note :G3) :duration 1/4}
   {:pitch (note :A3) :duration 1/8}
   {:pitch (note :G3) :duration 1/8}

   {:pitch (note :F3) :duration 3/8}
   {:pitch (note :D3) :duration 1/8}
   {:pitch (note :D3) :duration 1/4}
   {:pitch (note :C3) :duration 1/4}

   {:pitch (note :F3) :duration 3/4}
   ])

(defn play
  "Accepts a metronome, and a sequence of maps with :pitch and :duration.
  When the note sequence is empty, on the next beat
  a ::finished-playing event is triggered"
  [nome notes]
  (let [beat (nome)]
    (if-let [note (first notes)]
      (let [{pitch :pitch duration :duration} note
            decay (* (metro-tick nome) duration)]
        (when (some? pitch)
          (at (nome beat)
              (overtone.inst.synth/tb303
               :note pitch
               :cutoff 2000
               :decay (/ decay 1000)
               :wave 1
               :sustain 0.8
               :release 0.25
               :attack 0.1)))
        (apply-by (+ (nome (inc beat)) decay) #'play [nome (rest notes)]))
      (apply-at (nome (inc beat)) #'event [::finished-playing {:metronome nome}]))))

(defn -main
  [& args]
  (play (metronome 220) jingle-bells))
