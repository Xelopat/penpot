;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.plugins.format-test
  (:require
   [app.common.data :as d]
   [app.common.geom.point :as gpt]
   [app.plugins.exports :as exports]
   [app.plugins.format :as format]
   [app.plugins.parser :as parser]
   [app.plugins.shadows :as shadows]
   [app.plugins.tracks :as tracks]
   [cljs.test :as t :include-macros true]))

(t/deftest test-overlay-position-reference-survives-plugin-round-trip
  ;; Proxies are only handles here; these functions do not read shape state.
  (with-redefs [format/shape-proxy (fn [_plugin _file-id _page-id id]
                                     #js {:$id id})]
    (doseq [action-type [:open-overlay :toggle-overlay]]
      (t/testing (name action-type)
        (let [reference-id (random-uuid)
              interaction  {:action-type action-type
                            :destination (random-uuid)
                            :position-relative-to reference-id
                            :overlay-pos-type :center}
              action       (format/format-action interaction nil nil nil)
              parsed       (-> (parser/parse-action action)
                               (parser/resolve-animation :click)
                               (d/without-nils))]
          (t/is (= reference-id (aget (aget action "relativeTo") "$id")))
          (t/is (nil? (aget action "animation")))
          (t/is (= interaction parsed)))))))

(t/deftest test-new-actions-survive-plugin-round-trip
  (with-redefs [format/shape-proxy (fn [_plugin _file-id _page-id id]
                                     #js {:$id id})]
    (let [round-trip (fn [interaction]
                       (-> (format/format-action interaction nil nil nil)
                           (parser/parse-action)
                           (parser/resolve-animation :click)
                           (d/without-nils)))]

      (t/testing "viewport overlay"
        (let [interaction {:action-type :open-overlay
                           :destination (random-uuid)
                           :relative-to-viewport true
                           :overlay-pos-type :bottom-center
                           :animation {:animation-type :dissolve :duration 200 :easing :ease-out}}
              action      (format/format-action interaction nil nil nil)]
          (t/is (= "viewport" (aget action "relativeTo")))
          (t/is (= interaction (round-trip interaction)))))

      (t/testing "swap overlay"
        (let [interaction {:action-type :swap-overlay
                           :destination (random-uuid)
                           :position-relative-to (random-uuid)
                           :overlay-pos-type :top-left
                           :animation {:animation-type :smart-animate :duration 300 :easing :ease-out}}]
          (t/is (= "swap-overlay" (aget (format/format-action interaction nil nil nil) "type")))
          (t/is (= interaction (round-trip interaction)))))

      (t/testing "scroll to"
        (let [interaction {:action-type :scroll-to
                           :destination (random-uuid)
                           :scroll-offset (gpt/point 0 -64)
                           :animation {:animation-type :smart-animate :duration 500 :easing :ease-in-out}}
              action      (format/format-action interaction nil nil nil)]
          (t/is (= "scroll-to" (aget action "type")))
          (t/is (= -64 (aget (aget action "scrollOffset") "y")))
          (t/is (= interaction (round-trip interaction))))))))

(t/deftest test-format-array-always-returns-array
  (t/testing "nil collection returns an empty array"
    (let [result (format/format-array identity nil)]
      (t/is (array? result))
      (t/is (= 0 (.-length result)))))

  (t/testing "empty collection returns an empty array"
    (let [result (format/format-array identity [])]
      (t/is (array? result))
      (t/is (= 0 (.-length result)))))

  (t/testing "non-empty collection maps each item"
    (let [result (format/format-array inc [1 2 3])]
      (t/is (array? result))
      (t/is (= [2 3 4] (vec result)))))

  (t/testing "items dropped by format-fn (nil) are removed"
    (let [result (format/format-array #(when (odd? %) %) [1 2 3 4])]
      (t/is (= [1 3] (vec result))))))

(t/deftest test-array-wrappers-return-empty-array-on-nil
  ;; Each wrapper backs a non-nullable array-typed Plugin API property and
  ;; must return an empty array (never nil) when the source collection is nil.
  (t/are [result] (and (array? result) (= 0 (.-length result)))
    (shadows/format-shadows nil)
    (exports/format-exports nil)
    (format/format-frame-guides nil)
    (tracks/format-tracks nil nil nil nil nil nil)
    (format/format-path-content nil)))

(t/deftest test-format-color-result-uses-shapes-info-key
  (let [shape-id (random-uuid)
        result   (format/format-color-result
                  [{:color "#fabada"}
                   [{:prop :fill :shape-id shape-id :index 0}]])
        info     (aget result "shapesInfo")]
    (t/is (array? info))
    (t/is (nil? (aget result "shapesColors")))
    (t/is (= "fill" (aget (aget info 0) "property")))
    (t/is (= (str shape-id) (aget (aget info 0) "shapeId")))))

(t/deftest test-shape-type-reports-boolean
  (t/is (= "boolean" (format/shape-type :bool))))
