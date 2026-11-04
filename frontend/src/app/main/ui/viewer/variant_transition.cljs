;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.viewer.variant-transition
  "Animated replacement of a component copy by another of its variants.

  Before the copy is replaced, a picture of it is drawn over the page
  outside of React. Once the new variant is rendered, the picture either
  morphs into it (smart animate) or fades away while the new variant fades
  in, and is then removed."
  (:require
   [app.common.data.macros :as dm]
   [app.main.ui.viewer.smart-animate :as smart]
   [app.util.dom :as dom]))

(def ^:private max-wait-frames 30)
(def ^:private render-delay 32)

(defn- find-node
  "Rendered group of the shape `id`. Overlays are drawn after the screen,
  so the last copy is the visible one."
  [id]
  (let [nodes (dom/query-all (dm/str "[id=\"shape-" id "\"]"))
        n     (some-> nodes .-length)]
    (when (and n (pos? n))
      (aget nodes (dec n)))))

(defn- root-svg
  [^js node]
  (loop [svg (.-ownerSVGElement node)]
    (if-let [parent (some-> svg .-ownerSVGElement)]
      (recur parent)
      svg)))

(defn snapshot!
  "Picture of the rendered copy `id`, drawn over it and outside of React.
  Returns nil when the copy is not rendered as SVG."
  [id]
  (when-let [node (find-node id)]
    (when-let [svg (root-svg node)]
      (let [{:keys [left top width height]} (dom/get-bounding-rect svg)
            layer (dom/create-element "div")
            copy  (.cloneNode ^js svg false)]
        (set! (.. ^js layer -style -cssText)
              (dm/str "position:fixed;pointer-events:none;z-index:3;"
                      "left:" left "px;top:" top "px;width:" width "px;height:" height "px;"))
        (.appendChild ^js copy (.cloneNode ^js node true))
        (.appendChild ^js layer copy)
        (.appendChild (.-body js/document) layer)
        layer))))

(defn- dissolve!
  [^js layer ^js node animation done]
  (let [duration (:duration animation 0)
        timing   #js {:duration duration
                      :easing (name (:easing animation :ease-out))}]
    (if (pos? duration)
      (do
        (.animate node (clj->js [{:opacity 0} {:opacity 1}]) timing)
        (let [fade (.animate layer
                             (clj->js [{:opacity 1} {:opacity 0}])
                             #js {:duration duration
                                  :easing (.-easing timing)
                                  :fill "forwards"})]
          (set! (.-onfinish fade) done)
          ;; Animations of a hidden page may never report their end
          (js/setTimeout done (+ duration 200))))
      (done))))

(defn animate!
  "Transition from the picture `layer` of the copy `old-id` to its new
  variant `new-id` (usually the same id), once it is rendered. `get-objects` returns the shapes
  of both copies (page coordinates)."
  [{:keys [layer old-id new-id get-objects animation]}]
  (let [removed? (volatile! false)
        done     (fn []
                   (when-not @removed?
                     (vreset! removed? true)
                     (some-> ^js layer .remove)))
        start    (fn [^js node]
                   (let [container (or (.closest node "[id^='overlay-']")
                                       (dom/get-element "viewer-base-viewport"))]
                     (when-not (and (= :smart-animate (:animation-type animation))
                                    (some? container)
                                    (smart/animate! {:from-node layer
                                                     :to-node container
                                                     :from-frame-id old-id
                                                     :to-frame-id new-id
                                                     :objects (get-objects)
                                                     :animation animation
                                                     :animate-root? true
                                                     :on-complete done}))
                       (dissolve! layer node animation done))))
        wait     (fn wait [frames]
                   (if-let [node (find-node new-id)]
                     (start node)
                     (if (pos? frames)
                       (js/setTimeout #(wait (dec frames)) render-delay)
                       (done))))]
    (if (or (nil? layer) (nil? animation))
      (done)
      ;; The switched copy keeps its id, so wait for the new variant to be
      ;; drawn before looking it up. Timers, unlike animation frames, also
      ;; run in background tabs.
      (js/setTimeout #(wait max-wait-frames) render-delay))))
