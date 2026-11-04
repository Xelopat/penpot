import assert from "node:assert/strict";
import test from "node:test";
import {
    cssClass,
    interactionsToCss,
    layerDiff,
    motionTokensToCss,
    namePaths,
    type CssShape,
} from "./InteractionsCss.ts";

type Mutable = { -readonly [K in keyof CssShape]: CssShape[K] };

function shape(name: string, props: Partial<Mutable> = {}, parent: Mutable | null = null): Mutable {
    const s: Mutable = { id: `${name}-id`, name, x: 0, y: 0, width: 100, height: 50, children: [], parent, ...props };
    if (parent) parent.children = [...(parent.children ?? []), s];
    return s;
}

const root = () => shape("Root");

test("class names come from layer names", () => {
    assert.equal(cssClass({ name: "Open modal", id: "1" }), ".open-modal");
    assert.equal(cssClass({ name: "Кнопка Заказать", id: "1" }), ".кнопка-заказать");
    assert.equal(cssClass({ name: "1st", id: "abcdef123" }), ".layer-1st");
    assert.equal(cssClass({ name: "", id: "abcdef123" }), ".layer-abcdef12");
});

test("dissolve and slide overlays become keyframes", () => {
    const page = root();
    const home = shape("Home", {}, page);
    const modal = shape("Modal", {}, page);
    const button = shape("Open", {}, home);
    button.interactions = [
        {
            trigger: "click",
            action: {
                type: "open-overlay",
                destination: modal,
                animation: { type: "dissolve", duration: 250, easing: "ease-out" },
            },
        },
        {
            trigger: "click",
            action: {
                type: "toggle-overlay",
                destination: modal,
                animation: { type: "slide", direction: "up", duration: 300, easing: "ease-in" },
            },
        },
        {
            trigger: "click",
            action: { type: "close-overlay", destination: modal, animation: { type: "dissolve", duration: 200 } },
        },
    ];
    const result = interactionsToCss(button);
    assert.equal(result.items.length, 3);
    assert.match(result.items[0].css, /@keyframes modal-open-overlay[\s\S]*opacity: 0;[\s\S]*opacity: 1;/);
    assert.match(result.items[0].css, /\.modal \{\n  animation: modal-open-overlay 250ms ease-out both;/);
    assert.match(result.items[1].css, /from \{ transform: translateY\(100%\); \}/);
    assert.match(result.items[2].css, /from \{ opacity: 1; \}[\s\S]*to \{ opacity: 0; \}/);
    assert.match(result.items[2].css, /200ms linear/);
});

test("push moves both screens and after-delay becomes animation-delay", () => {
    const page = root();
    const home = shape("Home", {}, page);
    const next = shape("Next", {}, page);
    home.interactions = [
        {
            trigger: "after-delay",
            delay: 2000,
            action: {
                type: "navigate-to",
                destination: next,
                animation: { type: "push", direction: "left", duration: 400, easing: "ease" },
            },
        },
    ];
    const { css } = interactionsToCss(home);
    assert.match(css, /animation: next-navigate-to 400ms ease 2000ms both/);
    assert.match(css, /\.home \{\n  animation: next-navigate-to-leave 400ms ease 2000ms both;/);
    assert.match(css, /from \{ transform: translateX\(100%\); \}/);
});

test("hover overlays become :hover transitions with the changed properties", () => {
    const page = root();
    const home = shape("Home", {}, page);
    const card = shape("Card", { fills: [{ fillColor: "#CCCCCC", fillOpacity: 1 }] }, home);
    const hover = shape("Card hover", { x: 500, fills: [{ fillColor: "#3355FF", fillOpacity: 0.5 }] }, page);
    card.interactions = [
        {
            trigger: "mouse-over",
            action: {
                type: "open-overlay",
                destination: hover,
                animation: { type: "dissolve", duration: 150, easing: "ease-out" },
            },
        },
    ];
    const { css, items } = interactionsToCss(card);
    assert.match(css, /\.card \{\n  transition: background-color 150ms ease-out;/);
    assert.match(css, /\.card:hover \{\n  background-color: rgb\(51 85 255 \/ 0\.5\);/);
    assert.match(items[0].note, /Card hover/);
});

test("smart animate transitions only the properties that change between same-named layers", () => {
    const page = root();
    const stateA = shape("State A", { x: 1000 }, page);
    const stateB = shape("State B", { x: 2000 }, page);
    shape("Indicator", { x: 1000, width: 120, fills: [{ fillColor: "#0055FF" }] }, stateA);
    shape("Indicator", { x: 2140, width: 120, fills: [{ fillColor: "#00AA55" }] }, stateB);
    shape("Title", { x: 1020, y: 40 }, stateA);
    shape("Title", { x: 2020, y: 40 }, stateB);
    const tab = shape("Tab B", {}, stateA);
    tab.interactions = [
        {
            trigger: "click",
            action: {
                type: "swap-overlay",
                destination: stateB,
                animation: { type: "smart-animate", duration: 300, easing: "ease-out" },
            },
        },
    ];
    const { css, items } = interactionsToCss(tab);
    assert.match(css, /\.indicator \{\n  transition: transform 300ms ease-out, background-color 300ms ease-out;/);
    assert.match(
        css,
        /\.is-state-b \.indicator \{\n  transform: translate\(140px, 0px\);\n  background-color: #00AA55;/
    );
    assert.doesNotMatch(css, /\.title/);
    assert.match(items[0].note, /1 matching layer/);
});

test("scroll-to becomes an anchor with scroll margin", () => {
    const page = root();
    const home = shape("Home", {}, page);
    const contacts = shape("Contacts", {}, home);
    const link = shape("Link", {}, home);
    link.interactions = [
        {
            trigger: "click",
            action: {
                type: "scroll-to",
                destination: contacts,
                scrollOffset: { x: 0, y: -80 },
                animation: { type: "smart-animate", duration: 400 },
            },
        },
    ];
    const { css, items } = interactionsToCss(link);
    assert.match(css, /html \{\n  scroll-behavior: smooth;/);
    assert.match(css, /\.contacts \{\n  scroll-margin-top: 80px;/);
    assert.match(items[0].note, /#contacts/);
});

test("instant changes are reported without CSS", () => {
    const page = root();
    const home = shape("Home", {}, page);
    const next = shape("Next", {}, page);
    home.interactions = [{ trigger: "click", action: { type: "navigate-to", destination: next, animation: null } }];
    const result = interactionsToCss(home);
    assert.equal(result.css, "");
    assert.match(result.items[0].note, /Instant/);
});

test("layer diffs ignore unchanged properties and name paths skip duplicates", () => {
    const a = shape("A", { x: 10, y: 10, opacity: 1 });
    const b = shape("B", { x: 10, y: 10, opacity: 0.5, borderRadius: 8 });
    assert.deepEqual(layerDiff(a, b), { opacity: "0.5", "border-radius": "8px" });

    const board = shape("Board");
    shape("Item", {}, board);
    shape("Item", {}, board);
    const group = shape("Group", {}, board);
    shape("Label", {}, group);
    assert.deepEqual([...namePaths(board).keys()], ["Group", "Group/Label"]);
});

test("motion tokens become CSS custom properties", () => {
    const css = motionTokensToCss([
        { name: "motion.duration.fast", type: "number", value: "150" },
        { name: "motion.duration.slow", type: "number", resolvedValue: 400 },
        { name: "color.primary", type: "color", value: "#000" },
    ]);
    assert.equal(css, ":root {\n  --motion-duration-fast: 150ms;\n  --motion-duration-slow: 400ms;\n}");
    assert.equal(motionTokensToCss([]), "");
});
