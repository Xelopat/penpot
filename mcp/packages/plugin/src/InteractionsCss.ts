/**
 * Translation of prototype interactions into CSS for hand-off.
 *
 * The functions only read plain data from Plugin API objects, so they can be
 * tested with simple mocks. The result is a starting point that matches what
 * the client sees in the prototype; the developer adapts selectors and the
 * JavaScript that toggles classes.
 */

export interface CssAnimation {
    readonly type: string;
    readonly duration?: number;
    readonly easing?: string;
    readonly way?: string;
    readonly direction?: string;
}

export interface CssFill {
    readonly fillColor?: string;
    readonly fillOpacity?: number;
}

export interface CssShape {
    readonly id: string;
    readonly name: string;
    readonly type?: string;
    readonly x?: number;
    readonly y?: number;
    readonly width?: number;
    readonly height?: number;
    readonly opacity?: number;
    readonly rotation?: number;
    readonly borderRadius?: number;
    readonly fills?: readonly CssFill[] | string;
    readonly parent?: CssShape | null;
    readonly children?: readonly CssShape[];
    readonly interactions?: readonly CssInteraction[];
}

export interface CssInteraction {
    readonly trigger: string;
    readonly delay?: number | null;
    readonly action: {
        readonly type: string;
        readonly destination?: CssShape | null;
        readonly relativeTo?: CssShape | string | null;
        readonly position?: string;
        readonly scrollOffset?: { x: number; y: number } | null;
        readonly animation?: CssAnimation | null;
    };
}

export interface InteractionCss {
    /** Index of the interaction in `shape.interactions` */
    readonly index: number;
    readonly trigger: string;
    readonly action: string;
    readonly css: string;
    /** What the CSS expects from the page (classes to toggle, JavaScript) */
    readonly note: string;
}

export interface InteractionsCssResult {
    readonly css: string;
    readonly items: InteractionCss[];
}

const HOVER_TRIGGERS = ["mouse-enter", "mouse-over"];

/** CSS class for a layer, derived from its name */
export function cssClass(shape: Pick<CssShape, "name" | "id">): string {
    const slug = (shape.name || "")
        .trim()
        .toLowerCase()
        .replace(/[^\p{L}\p{N}_-]+/gu, "-")
        .replace(/^-+|-+$/g, "");
    const safe = slug && !/^[0-9-]/.test(slug) ? slug : `layer-${slug || shape.id.slice(0, 8)}`;
    return `.${safe}`;
}

function ms(value: number | undefined | null): string {
    return `${Math.round(value ?? 0)}ms`;
}

function timing(animation: CssAnimation | null | undefined, delay?: number | null): string {
    const duration = ms(animation?.duration ?? 0);
    const easing = animation?.easing ?? "linear";
    return delay ? `${duration} ${easing} ${ms(delay)}` : `${duration} ${easing}`;
}

/** Offset (as a CSS translate) the element starts from when entering moving towards `direction` */
function enterOffset(direction: string | undefined): string {
    switch (direction) {
        case "left":
            return "translateX(100%)";
        case "up":
            return "translateY(100%)";
        case "down":
            return "translateY(-100%)";
        default:
            return "translateX(-100%)";
    }
}

/** Offset the element ends at when leaving moving towards `direction` */
function leaveOffset(direction: string | undefined): string {
    switch (direction) {
        case "left":
            return "translateX(-100%)";
        case "up":
            return "translateY(-100%)";
        case "down":
            return "translateY(100%)";
        default:
            return "translateX(100%)";
    }
}

function keyframes(name: string, from: string, to: string): string {
    return `@keyframes ${name} {\n  from { ${from} }\n  to { ${to} }\n}`;
}

function rule(selector: string, declarations: string[]): string {
    return `${selector} {\n${declarations.map((d) => `  ${d};`).join("\n")}\n}`;
}

function firstFill(shape: CssShape): string | undefined {
    const fills = Array.isArray(shape.fills) ? shape.fills : [];
    const fill = fills[0];
    if (!fill?.fillColor) return undefined;
    const opacity = fill.fillOpacity ?? 1;
    if (opacity >= 1) return fill.fillColor;
    const hex = fill.fillColor.replace("#", "");
    const [r, g, b] = [0, 2, 4].map((i) => parseInt(hex.slice(i, i + 2), 16));
    return `rgb(${r} ${g} ${b} / ${Math.round(opacity * 100) / 100})`;
}

/**
 * CSS declarations that turn layer `from` into layer `to`, relative to their
 * own boards (`fromBase`, `toBase`). Only properties that really change are
 * returned.
 */
export function layerDiff(
    from: CssShape,
    to: CssShape,
    fromBase?: CssShape | null,
    toBase?: CssShape | null
): Record<string, string> {
    const out: Record<string, string> = {};
    const dx = (to.x ?? 0) - (toBase?.x ?? 0) - ((from.x ?? 0) - (fromBase?.x ?? 0));
    const dy = (to.y ?? 0) - (toBase?.y ?? 0) - ((from.y ?? 0) - (fromBase?.y ?? 0));
    const rotation = (to.rotation ?? 0) - (from.rotation ?? 0);
    const transforms: string[] = [];
    if (Math.abs(dx) > 0.5 || Math.abs(dy) > 0.5)
        transforms.push(`translate(${Math.round(dx)}px, ${Math.round(dy)}px)`);
    if (Math.abs(rotation) > 0.1) transforms.push(`rotate(${Math.round(rotation * 10) / 10}deg)`);
    if (transforms.length) out.transform = transforms.join(" ");
    if (Math.abs((to.width ?? 0) - (from.width ?? 0)) > 0.5) out.width = `${Math.round(to.width ?? 0)}px`;
    if (Math.abs((to.height ?? 0) - (from.height ?? 0)) > 0.5) out.height = `${Math.round(to.height ?? 0)}px`;
    if (Math.abs((to.opacity ?? 1) - (from.opacity ?? 1)) > 0.001) out.opacity = `${to.opacity ?? 1}`;
    const fromFill = firstFill(from);
    const toFill = firstFill(to);
    if (toFill && toFill !== fromFill) out["background-color"] = toFill;
    if ((to.borderRadius ?? 0) !== (from.borderRadius ?? 0)) out["border-radius"] = `${to.borderRadius ?? 0}px`;
    return out;
}

/** Map of name path ("Card/Title") -> layer, for the subtree of `root`; ambiguous paths are left out */
export function namePaths(root: CssShape): Map<string, CssShape> {
    const found = new Map<string, CssShape[]>();
    const walk = (shape: CssShape, path: string) => {
        for (const child of shape.children ?? []) {
            const childPath = path ? `${path}/${child.name}` : child.name;
            found.set(childPath, [...(found.get(childPath) ?? []), child]);
            walk(child, childPath);
        }
    };
    walk(root, "");
    return new Map([...found].filter(([, shapes]) => shapes.length === 1).map(([path, shapes]) => [path, shapes[0]]));
}

/** Top-level board holding a shape */
export function rootBoard(shape: CssShape): CssShape {
    let current = shape;
    while (current.parent && current.parent.parent) current = current.parent;
    return current;
}

function smartAnimateCss(from: CssShape, to: CssShape, animation: CssAnimation): { css: string; layers: number } {
    const fromPaths = namePaths(from);
    const toPaths = namePaths(to);
    const blocks: string[] = [];
    for (const [path, toLayer] of toPaths) {
        const fromLayer = fromPaths.get(path);
        if (!fromLayer) continue;
        const diff = layerDiff(fromLayer, toLayer, from, to);
        const props = Object.keys(diff);
        if (!props.length) continue;
        const selector = path
            .split("/")
            .map((name) => cssClass({ name, id: toLayer.id }))
            .join(" ");
        const transition = props.map((p) => `${p} ${timing(animation)}`).join(", ");
        blocks.push(rule(selector, [`transition: ${transition}`]));
        blocks.push(
            rule(
                `.is-${cssClass(to).slice(1)} ${selector}`,
                Object.entries(diff).map(([k, v]) => `${k}: ${v}`)
            )
        );
    }
    return { css: blocks.join("\n\n"), layers: blocks.length / 2 };
}

/**
 * CSS for the prototype interactions of `shape`. See `InteractionsCss` for
 * the meaning of each item.
 */
export function interactionsToCss(shape: CssShape): InteractionsCssResult {
    const items: InteractionCss[] = [];
    const trigger = cssClass(shape);

    (shape.interactions ?? []).forEach((interaction, index) => {
        const { action } = interaction;
        const animation = action.animation ?? null;
        const destination = action.destination ?? null;
        const target = destination ? cssClass(destination) : null;
        const delay = interaction.trigger === "after-delay" ? (interaction.delay ?? 0) : null;
        const push = (css: string, note: string) =>
            items.push({ index, trigger: interaction.trigger, action: action.type, css, note });

        // Hover states: an overlay opened while hovering, positioned on the element
        if (
            HOVER_TRIGGERS.includes(interaction.trigger) &&
            (action.type === "open-overlay" || action.type === "toggle-overlay") &&
            destination
        ) {
            const diff = layerDiff(shape, destination, shape, destination);
            delete diff.transform;
            const props = Object.keys(diff);
            const transition = props.length
                ? props.map((p) => `${p} ${timing(animation)}`).join(", ")
                : `all ${timing(animation)}`;
            const css = [
                rule(trigger, [`transition: ${transition}`]),
                rule(
                    `${trigger}:hover`,
                    props.length ? Object.entries(diff).map(([k, v]) => `${k}: ${v}`) : ["/* hover look */"]
                ),
            ].join("\n\n");
            push(css, `Hover state "${destination.name}" written as :hover on the element.`);
            return;
        }

        if (action.type === "scroll-to" && destination) {
            const offset = -(action.scrollOffset?.y ?? 0);
            const declarations = [`scroll-margin-top: ${Math.max(0, offset)}px`];
            const css = [rule("html", ["scroll-behavior: smooth"]), rule(target!, declarations)].join("\n\n");
            const duration = animation ? ` CSS cannot set the ${ms(animation.duration)} duration; use JS for it.` : "";
            push(css, `Link to #${target!.slice(1)} (anchor href).${duration}`);
            return;
        }

        if (!animation || !target) {
            if (action.type !== "open-url" && action.type !== "previous-screen") {
                push("", "Instant change: no animation to translate.");
            }
            return;
        }

        const name = `${target.slice(1)}-${action.type}`;
        const t = timing(animation, delay);

        if (animation.type === "smart-animate" && (action.type === "navigate-to" || action.type === "swap-overlay")) {
            // The previous state is the board holding the trigger (a screen, or
            // the state overlay that contains the button)
            const { css, layers } = smartAnimateCss(rootBoard(shape), destination!, animation);
            push(
                css,
                `Add class ".is-${target.slice(1)}" to the shared container to switch to "${destination!.name}"; ` +
                    `${layers} matching layer(s) transition.`
            );
            return;
        }

        const closing = action.type === "close-overlay";
        if (animation.type === "dissolve" || animation.type === "smart-animate") {
            const css = [
                keyframes(name, closing ? "opacity: 1;" : "opacity: 0;", closing ? "opacity: 0;" : "opacity: 1;"),
                rule(target, [`animation: ${name} ${t} both`]),
            ].join("\n\n");
            push(
                css,
                closing
                    ? `Play when closing "${destination!.name}", then remove it.`
                    : `Play when "${destination!.name}" appears.`
            );
            return;
        }

        if (animation.type === "slide") {
            const leaving = closing || (action.type === "navigate-to" && animation.way === "out");
            const from = leaving ? "transform: none;" : `transform: ${enterOffset(animation.direction)};`;
            const to = leaving ? `transform: ${leaveOffset(animation.direction)};` : "transform: none;";
            const css = [keyframes(name, from, to), rule(target, [`animation: ${name} ${t} both`])].join("\n\n");
            push(css, leaving ? "Applied to the element that leaves." : "Applied to the element that enters.");
            return;
        }

        if (animation.type === "push") {
            const leaveName = `${name}-leave`;
            const css = [
                keyframes(name, `transform: ${enterOffset(animation.direction)};`, "transform: none;"),
                keyframes(leaveName, "transform: none;", `transform: ${leaveOffset(animation.direction)};`),
                rule(target, [`animation: ${name} ${t} both`]),
                rule(cssClass(rootBoard(shape)), [`animation: ${leaveName} ${t} both`]),
            ].join("\n\n");
            push(css, "The new screen pushes the current one out; run both animations together.");
        }
    });

    return {
        css: items
            .map((item) => item.css)
            .filter(Boolean)
            .join("\n\n"),
        items,
    };
}

export interface MotionToken {
    readonly name: string;
    readonly type?: string;
    readonly value?: unknown;
    readonly resolvedValue?: unknown;
}

/**
 * CSS custom properties for motion tokens. Penpot has no duration token
 * type, so the convention is `number` tokens named `motion.duration.<name>`
 * holding milliseconds, e.g. `motion.duration.fast = 150`. Other `motion.*`
 * tokens are written with their value as is.
 */
export function motionTokensToCss(tokens: readonly MotionToken[]): string {
    const lines: string[] = [];
    for (const token of tokens) {
        if (!token.name.startsWith("motion.")) continue;
        const value = token.resolvedValue ?? token.value;
        if (value === undefined || value === null || value === "") continue;
        const variable = `--${token.name.replace(/\./g, "-")}`;
        const isDuration = token.name.startsWith("motion.duration.");
        const css = isDuration && /^-?\d+(\.\d+)?$/.test(String(value)) ? `${value}ms` : String(value);
        lines.push(`  ${variable}: ${css};`);
    }
    return lines.length ? `:root {\n${lines.join("\n")}\n}` : "";
}
