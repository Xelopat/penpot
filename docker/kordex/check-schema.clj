;; Checks that a backend jar accepts the prototype data written by the fork
;; frontend: an `:add-obj` change, as sent when a shape is duplicated or
;; pasted, carrying every new interaction, animation and board option.
;;
;;   java -cp penpot.jar clojure.main check-schema.clj

(require '[app.common.files.changes :as cpc]
         '[app.common.geom.point :as gpt]
         '[app.common.schema :as sm]
         '[app.common.types.shape :as cts]
         '[app.common.uuid :as uuid])

(def target (uuid/next))

(def interactions
  [{:event-type :click
    :action-type :open-overlay
    :destination target
    :overlay-pos-type :center
    :overlay-position (gpt/point 0 0)
    :relative-to-viewport true
    :animation {:animation-type :dissolve :duration 250 :easing :ease-out}}
   {:event-type :click
    :action-type :swap-overlay
    :destination target
    :overlay-pos-type :top-left
    :overlay-position (gpt/point 0 0)
    :position-relative-to target
    :animation {:animation-type :smart-animate :duration 300 :easing :ease-out}}
   {:event-type :click
    :action-type :scroll-to
    :destination target
    :scroll-offset (gpt/point 0 -80)
    :animation {:animation-type :smart-animate :duration 400 :easing :ease-in-out}}
   {:event-type :mouse-over
    :action-type :navigate
    :destination target
    :animation {:animation-type :smart-animate :duration 300 :easing :ease-out}}])

(def frame
  (-> (cts/setup-shape {:type :frame :name "Board" :x 0 :y 0 :width 400 :height 300})
      (assoc :interactions interactions
             :scroll-overflow :horizontal
             :scroll-snap true)))

(def change
  {:type :add-obj
   :id (:id frame)
   :page-id (uuid/next)
   :frame-id uuid/zero
   :parent-id uuid/zero
   :obj frame})

(if (sm/validate cpc/schema:changes [change])
  (println :kordex-schema-ok)
  (do (println :kordex-schema-rejected)
      (System/exit 1)))
