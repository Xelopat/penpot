;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns frontend-tests.plugins.parser-test
  (:require
   [app.common.geom.point :as gpt]
   [app.common.schema :as sm]
   [app.common.types.grid :as ctg]
   [app.common.types.shape.interactions :as ctsi]
   [app.common.uuid :as uuid]
   [app.plugins.parser :as parser]
   [cljs.test :as t :include-macros true]))

(defn- overlay-action
  [{:keys [type destination relative-to position manual-position-location]}]
  (let [action (js-obj "type" type
                       "destination" (js-obj "$id" destination))]
    (when (some? relative-to)
      (unchecked-set action "relativeTo" (js-obj "$id" relative-to)))
    (when (some? position)
      (unchecked-set action "position" position))
    (when (some? manual-position-location)
      (unchecked-set action "manualPositionLocation" manual-position-location))
    action))

(defn- parse-overlay-interaction
  [action]
  (parser/parse-interaction "click" (overlay-action action) nil))

(defn- valid-interaction?
  [interaction]
  (sm/validate ctsi/schema:interaction interaction))

(t/deftest test-animation-default-easing-validates
  (doseq [animation [#js {:type "dissolve" :duration 150}
                     #js {:type "slide" :way "in" :direction "up" :duration 150}
                     #js {:type "push" :direction "left" :duration 150}]]
    (t/testing (aget animation "type")
      (let [parsed (parser/parse-animation animation)]
        (t/is (= :linear (:easing parsed)))
        (t/is (sm/validate ctsi/schema:animation parsed)))))

  (t/testing "an explicit easing is preserved"
    (t/is (= :ease-out
             (:easing (parser/parse-animation
                       #js {:type "dissolve" :duration 150 :easing "ease-out"}))))))

(t/deftest test-overlay-relative-to-uses-viewer-position-reference
  (doseq [action-type ["open-overlay" "toggle-overlay"]]
    (t/testing action-type
      (let [reference-id (uuid/next)
            result       (parse-overlay-interaction
                          {:type action-type
                           :destination (uuid/next)
                           :relative-to reference-id})]
        (t/is (= reference-id (:position-relative-to result)))
        (t/is (not (contains? result :relative-to)))
        (t/is (valid-interaction? result)))))

  (t/testing "an omitted reference positions the overlay in the viewport"
    (let [result (parse-overlay-interaction
                  {:type "open-overlay" :destination (uuid/next)})]
      (t/is (true? (:relative-to-viewport result)))
      (t/is (not (contains? result :position-relative-to)))
      (t/is (valid-interaction? result))))

  (t/testing "an omitted reference of a manual position stays on the trigger"
    (let [result (parse-overlay-interaction
                  {:type "open-overlay" :destination (uuid/next) :position "manual"})]
      (t/is (not (contains? result :relative-to-viewport)))))

  (t/testing "a null reference keeps the base board positioning"
    (let [action (overlay-action {:type "open-overlay" :destination (uuid/next)})
          _      (unchecked-set action "relativeTo" nil)
          result (parser/parse-interaction "click" action nil)]
      (t/is (not (contains? result :relative-to-viewport)))
      (t/is (not (contains? result :position-relative-to)))))

  (t/testing "the viewport reference"
    (doseq [action-type ["open-overlay" "toggle-overlay" "swap-overlay"]]
      (let [action (overlay-action {:type action-type :destination (uuid/next)})
            _      (unchecked-set action "relativeTo" "viewport")
            result (parser/parse-interaction "click" action nil)]
        (t/is (true? (:relative-to-viewport result)))
        (t/is (valid-interaction? result))))))

(t/deftest test-parse-point-returns-gpt-point-record
  ;; Regression test for issue #8409.
  ;;
  ;; The plugin parser used to return a plain map `{:x … :y …}`, but the
  ;; shape-interaction schema expects `::gpt/point` (a Point record).
  ;; Plugin `addInteraction` calls with an `open-overlay` action and
  ;; `manualPositionLocation` were silently rejected by validation.
  (t/testing "parse-point returns nil for nil input"
    (t/is (nil? (parser/parse-point nil))))

  (t/testing "parse-point returns a gpt/point record for valid input"
    (let [result (parser/parse-point #js {:x 10 :y 20})]
      (t/is (gpt/point? result))
      (t/is (= 10 (:x result)))
      (t/is (= 20 (:y result)))))

  (t/testing "parse-point passes gpt/point? for a zero point"
    (let [result (parser/parse-point #js {:x 0 :y 0})]
      (t/is (gpt/point? result))
      (t/is (= 0 (:x result)))
      (t/is (= 0 (:y result))))))

(t/deftest test-parse-frame-guide-calls-guide-parser
  (let [column (parser/parse-frame-guide
                #js {:type "column"
                     :display true
                     :params #js {:type "stretch"
                                  :size 12}})
        row    (parser/parse-frame-guide
                #js {:type "row"
                     :display false
                     :params #js {:type "center"
                                  :margin 4}})]
    (t/is (= :column (:type column)))
    (t/is (= true (:display column)))
    (t/is (= :stretch (get-in column [:params :type])))
    (t/is (= :row (:type row)))
    (t/is (= false (:display row)))
    (t/is (= :center (get-in row [:params :type])))))

(t/deftest test-parse-frame-guides
  ;; Regression test for issue #9773.
  ;;
  ;; `parse-frame-guide` returned the parser fns for column/row instead of
  ;; calling them with the guide, and the `board.guides` setter validated
  ;; against an unregistered `::ctg/grid` reference (now `ctg/schema:grid`).
  ;; Parsed guides must be plain maps that validate against the same direct
  ;; schema the setter uses, and clearing (empty input) must validate too.
  (let [column #js {:type "column" :display true
                    :params #js {:color #js {:color "#DE4762" :opacity 0.2}
                                 :type "stretch" :size 12 :gutter 16 :margin 16}}
        square #js {:type "square" :display true
                    :params #js {:color #js {:color "#DE4762" :opacity 0.2} :size 8}}
        parsed (parser/parse-frame-guides #js [column square])]
    (t/is (= :column (-> parsed first :type)))
    (t/is (= :square (-> parsed second :type)))
    (t/is (map? (-> parsed first :params)))
    (t/is (sm/validate [:vector ctg/schema:grid] parsed)))

  (t/testing "clearing guides with an empty vector validates"
    (t/is (sm/validate [:vector ctg/schema:grid] (parser/parse-frame-guides #js [])))))

(t/deftest test-parse-overlay-action-position-is-optional
  (t/testing "open-overlay defaults omitted position to center"
    (let [destination (uuid/next)
          result      (parse-overlay-interaction {:type "open-overlay"
                                                  :destination destination})]
      (t/is (= :open-overlay (:action-type result)))
      (t/is (= :click (:event-type result)))
      (t/is (= destination (:destination result)))
      (t/is (= :center (:overlay-pos-type result)))
      (t/is (not (contains? result :overlay-position)))
      (t/is (valid-interaction? result))))

  (t/testing "toggle-overlay preserves manualPositionLocation"
    (let [destination (uuid/next)
          result      (parse-overlay-interaction
                       {:type "toggle-overlay"
                        :destination destination
                        :position "manual"
                        :manual-position-location #js {:x 10 :y 20}})
          position    (:overlay-position result)]
      (t/is (= :toggle-overlay (:action-type result)))
      (t/is (= :manual (:overlay-pos-type result)))
      (t/is (gpt/point? position))
      (t/is (= 10 (:x position)))
      (t/is (= 20 (:y position)))
      (t/is (valid-interaction? result))))

  (t/testing "explicit center position does not require manualPositionLocation"
    (let [destination (uuid/next)
          result      (parse-overlay-interaction {:type "open-overlay"
                                                  :destination destination
                                                  :position "center"})]
      (t/is (= :center (:overlay-pos-type result)))
      (t/is (not (contains? result :overlay-position)))
      (t/is (valid-interaction? result))))

  (t/testing "manual position without manualPositionLocation still parses"
    (let [destination (uuid/next)
          result      (parse-overlay-interaction {:type "open-overlay"
                                                  :destination destination
                                                  :position "manual"})]
      (t/is (= :manual (:overlay-pos-type result)))
      (t/is (not (contains? result :overlay-position)))
      (t/is (valid-interaction? result)))))

(t/deftest test-parse-close-overlay-without-animation-validates
  (t/testing "close-overlay with a null animation parses and validates"
    (let [result (parser/parse-interaction "click" #js {:type "close-overlay" :animation nil} nil)]
      (t/is (= {:event-type :click
                :action-type :close-overlay}
               result))
      (t/is (false? (contains? result :animation)))
      (t/is (true? (sm/validate ctsi/schema:interaction result)))))

  (t/testing "close-overlay preserves destination without animation"
    (let [destination-id (uuid/next)
          result         (parser/parse-interaction
                          "click"
                          #js {:type "close-overlay"
                               :destination #js {"$id" destination-id}
                               :animation nil}
                          nil)]
      (t/is (= destination-id (:destination result)))
      (t/is (false? (contains? result :animation)))
      (t/is (true? (sm/validate ctsi/schema:interaction result)))))

  (t/testing "close-overlay preserves an explicit dissolve animation"
    (let [result (parser/parse-interaction
                  "click"
                  #js {:type "close-overlay"
                       :animation #js {:type "dissolve"
                                       :duration 300
                                       :easing "linear"}}
                  nil)]
      (t/is (= {:animation-type :dissolve
                :duration 300
                :easing :linear}
               (:animation result)))
      (t/is (true? (sm/validate ctsi/schema:interaction result))))))

(t/deftest test-default-animation
  (t/testing "an omitted animation gets a short dissolve"
    (let [result (parser/parse-interaction "click" #js {:type "close-overlay"} nil)]
      (t/is (= {:animation-type :dissolve :duration 250 :easing :ease-out}
               (:animation result)))
      (t/is (valid-interaction? result))))

  (t/testing "hover transitions are shorter"
    (let [result (parse-overlay-interaction {:type "open-overlay" :destination (uuid/next)})
          hover  (parser/parse-interaction "mouse-enter"
                                           (overlay-action {:type "open-overlay"
                                                            :destination (uuid/next)})
                                           nil)]
      (t/is (= 250 (-> result :animation :duration)))
      (t/is (= 150 (-> hover :animation :duration)))))

  (t/testing "actions without animation get none"
    (let [result (parser/parse-interaction "click" #js {:type "open-url" :url "https://penpot.app"} nil)]
      (t/is (not (contains? result :animation)))
      (t/is (valid-interaction? result))))

  (t/testing "resolve-animation turns null into a removal for patching"
    (t/is (= {:action-type :navigate :animation nil}
             (parser/resolve-animation
              (parser/parse-action #js {:type "navigate-to" :animation nil})
              :click)))))

(t/deftest test-parse-smart-animate
  (t/testing "smart animate defaults"
    (let [parsed (parser/parse-animation #js {:type "smart-animate"})]
      (t/is (= {:animation-type :smart-animate :duration 300 :easing :ease-out} parsed))
      (t/is (sm/validate ctsi/schema:animation parsed))))

  (t/testing "smart animate on navigate"
    (let [result (parser/parse-interaction
                  "click"
                  #js {:type "navigate-to"
                       :destination #js {"$id" (uuid/next)}
                       :animation #js {:type "smart-animate" :duration 400 :easing "ease-in-out"}}
                  nil)]
      (t/is (= {:animation-type :smart-animate :duration 400 :easing :ease-in-out}
               (:animation result)))
      (t/is (valid-interaction? result)))))

(t/deftest test-parse-scroll-to
  (let [destination (uuid/next)]
    (t/testing "scroll to with offset and default smooth scroll"
      (let [result (parser/parse-interaction
                    "click"
                    #js {:type "scroll-to"
                         :destination #js {"$id" destination}
                         :scrollOffset #js {:x 0 :y -80}}
                    nil)]
        (t/is (= :scroll-to (:action-type result)))
        (t/is (= destination (:destination result)))
        (t/is (= (gpt/point 0 -80) (:scroll-offset result)))
        (t/is (= {:animation-type :smart-animate :duration 400 :easing :ease-in-out}
                 (:animation result)))
        (t/is (valid-interaction? result))))

    (t/testing "instant scroll"
      (let [result (parser/parse-interaction
                    "click"
                    #js {:type "scroll-to"
                         :destination #js {"$id" destination}
                         :animation nil}
                    nil)]
        (t/is (not (contains? result :animation)))
        (t/is (valid-interaction? result))))))

(t/deftest test-parse-swap-overlay
  (let [destination (uuid/next)
        anchor      (uuid/next)
        result      (parse-overlay-interaction {:type "swap-overlay"
                                                :destination destination
                                                :relative-to anchor
                                                :position "top-left"})]
    (t/is (= :swap-overlay (:action-type result)))
    (t/is (= anchor (:position-relative-to result)))
    (t/is (= :top-left (:overlay-pos-type result)))
    (t/is (valid-interaction? result))))
