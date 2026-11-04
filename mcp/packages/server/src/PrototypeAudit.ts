/** Read-only subset of the Plugin API used by the prototype audit. */
export interface AuditShape {
    readonly id: string;
    readonly name: string;
    readonly type: string;
    readonly width: number;
    readonly hidden?: boolean;
    readonly fixedWhenScrolling?: boolean;
    readonly showInViewMode?: boolean;
    readonly parent?: AuditShape | null;
    readonly interactions?: readonly { readonly action: AuditAction }[];
}

export interface AuditAction {
    readonly type: string;
    readonly destination?: AuditShape | null;
    readonly relativeTo?: AuditShape | "viewport" | null;
    readonly variant?: string | null;
}

const OVERLAY_ACTIONS = ["open-overlay", "toggle-overlay", "swap-overlay"];

export interface AuditPage {
    readonly id: string;
    readonly name: string;
    readonly root: AuditShape;
    readonly flows: readonly { readonly name: string; readonly startingBoard?: AuditShape | null }[];
    findShapes(): AuditShape[];
}

export interface AuditContext {
    readonly version?: string | null;
    readonly currentFile: {
        readonly id: string;
        readonly name: string;
        readonly pages: readonly AuditPage[];
    } | null;
    readonly currentPage: AuditPage | null;
}

export interface PrototypeAuditOptions {
    pageId?: string;
    maxIssues?: number;
    sameWidthNavigation?: boolean;
    expectFixedOverlays?: boolean;
}

export interface PrototypeAuditIssue {
    severity: "error" | "warning";
    code: string;
    shape?: { id: string; name: string; hidden: boolean; board?: { id: string; name: string; width: number } };
    flow?: string;
    destinationId?: string;
    referenceId?: string;
    message: string;
}

/**
 * Structural prototype inspector executed in the browser's plugin context.
 * All runtime dependencies live inside this class so it can be serialized.
 */
export class PrototypeAudit {
    private static label(value: string): string {
        return value.slice(0, 96);
    }

    private static owner(
        shape: AuditShape,
        shapes: Map<string, AuditShape>,
        rootId: string,
        cache: Map<string, AuditShape | undefined>
    ): AuditShape | undefined {
        const path: string[] = [];
        const seen = new Set<string>();
        let current: AuditShape | undefined = shape;
        let result: AuditShape | undefined;
        while (current && !seen.has(current.id)) {
            if (cache.has(current.id)) {
                result = cache.get(current.id);
                break;
            }
            seen.add(current.id);
            path.push(current.id);
            const parent = current.parent;
            if (!parent || parent.id === rootId) {
                result = current.type === "board" ? current : undefined;
                break;
            }
            current = shapes.get(parent.id);
        }
        for (const id of path) cache.set(id, result);
        return result;
    }

    private static fixed(shape: AuditShape, shapes: Map<string, AuditShape>, cache: Map<string, boolean>): boolean {
        const path: string[] = [];
        const seen = new Set<string>();
        let current: AuditShape | undefined = shape;
        let result = false;
        while (current && !seen.has(current.id)) {
            if (cache.has(current.id)) {
                result = cache.get(current.id)!;
                break;
            }
            seen.add(current.id);
            path.push(current.id);
            if (current.fixedWhenScrolling) {
                result = true;
                break;
            }
            current = current.parent ? shapes.get(current.parent.id) : undefined;
        }
        for (const id of path) cache.set(id, result);
        return result;
    }

    public static run(context: AuditContext, options: PrototypeAuditOptions = {}) {
        const file = context.currentFile;
        if (!file) throw new Error("Open a Penpot file in the connected editor before auditing.");
        const page = options.pageId
            ? file.pages.find((candidate) => candidate.id === options.pageId)
            : context.currentPage;
        if (!page) throw new Error("The requested page is not available in the connected file.");
        const limit = options.maxIssues ?? 20;
        if (!Number.isInteger(limit) || limit < 0 || limit > 100) {
            throw new Error("maxIssues must be an integer between 0 and 100.");
        }

        const all = page.findShapes();
        const shapes = new Map(all.map((shape) => [shape.id, shape]));
        const owners = new Map<string, AuditShape | undefined>();
        const fixed = new Map<string, boolean>();
        const errors: PrototypeAuditIssue[] = [];
        const warnings: PrototypeAuditIssue[] = [];
        const counts: Record<string, number> = {};
        const actions = new Map<string, number>();
        const overlayTargets = new Set<string>();
        const screenTargets = new Set(page.flows.map((flow) => flow.startingBoard?.id).filter(Boolean));
        const sources: { shape: AuditShape; interactions: NonNullable<AuditShape["interactions"]> }[] = [];
        for (const shape of all) {
            const interactions = shape.interactions ?? [];
            if (interactions.length) sources.push({ shape, interactions });
            for (const { action } of interactions) {
                const id = action.destination?.id;
                if (!id) continue;
                if (action.type === "navigate-to") screenTargets.add(id);
                if (OVERLAY_ACTIONS.includes(action.type)) overlayTargets.add(id);
            }
        }
        let errorCount = 0;
        let warningCount = 0;
        let interactionCount = 0;

        const add = (issue: PrototypeAuditIssue) => {
            counts[issue.code] = (counts[issue.code] ?? 0) + 1;
            if (issue.severity === "error") {
                errorCount++;
                if (errors.length < limit) errors.push(issue);
            } else {
                warningCount++;
                if (warnings.length < limit) warnings.push(issue);
            }
        };

        for (const { shape, interactions } of sources) {
            const owner = this.owner(shape, shapes, page.root.id, owners);
            const source = {
                id: shape.id,
                name: this.label(shape.name),
                hidden: !!shape.hidden,
                board: owner ? { id: owner.id, name: this.label(owner.name), width: owner.width } : undefined,
            };
            const overlayOnly = !!owner && overlayTargets.has(owner.id) && !screenTargets.has(owner.id);
            if (!owner) {
                add({
                    severity: "warning",
                    code: "source-outside-screen",
                    shape: source,
                    message: "The interaction source has no top-level board on this page.",
                });
            }
            for (const { action } of interactions) {
                interactionCount++;
                actions.set(action.type, (actions.get(action.type) ?? 0) + 1);
                const overlay = OVERLAY_ACTIONS.includes(action.type);
                const navigate = action.type === "navigate-to";
                const destinationId = action.destination?.id;
                const destination = destinationId ? shapes.get(destinationId) : undefined;
                if (action.type === "change-to" && !action.variant) {
                    add({
                        severity: "error",
                        code: "variant-missing",
                        shape: source,
                        message: "The change-to interaction has no variant to switch to.",
                    });
                }
                if (action.type === "scroll-to" && !destination) {
                    add({
                        severity: "error",
                        code: "destination-missing",
                        shape: source,
                        destinationId,
                        message: "The scroll destination does not exist on this page.",
                    });
                }
                if (navigate || overlay || (action.type === "close-overlay" && action.destination)) {
                    if (!destination) {
                        add({
                            severity: "error",
                            code: "destination-missing",
                            shape: source,
                            destinationId,
                            message: "The destination does not exist on this page.",
                        });
                    } else if (destination.type !== "board") {
                        add({
                            severity: "error",
                            code: "destination-not-board",
                            shape: source,
                            destinationId,
                            message: "The destination is not a board.",
                        });
                    } else if (navigate && destination.showInViewMode === false) {
                        add({
                            severity: "error",
                            code: "destination-excluded-from-view",
                            shape: source,
                            destinationId,
                            message: "Navigation targets a board excluded from View mode.",
                        });
                    }
                    if (
                        navigate &&
                        destination?.type === "board" &&
                        owner &&
                        options.sameWidthNavigation &&
                        !overlayOnly &&
                        Math.abs(destination.width - owner.width) > 0.1
                    ) {
                        add({
                            severity: "warning",
                            code: "navigation-width-mismatch",
                            shape: source,
                            destinationId,
                            message: `Navigation changes board width from ${owner.width} to ${destination.width}; confirm this is intended.`,
                        });
                    }
                }
                // A viewport overlay sits in the visible area and stays fixed while scrolling
                const relativeTo = action.relativeTo;
                const viewport = relativeTo === "viewport";
                if (overlay && relativeTo && relativeTo !== "viewport") {
                    const referenceId = relativeTo.id;
                    const reference = referenceId ? shapes.get(referenceId) : undefined;
                    if (!reference) {
                        add({
                            severity: "error",
                            code: "reference-missing",
                            shape: source,
                            referenceId,
                            message: "The positioning reference is missing or has an empty proxy ID.",
                        });
                    } else {
                        if (owner && this.owner(reference, shapes, page.root.id, owners)?.id !== owner.id) {
                            add({
                                severity: "warning",
                                code: "reference-outside-screen",
                                shape: source,
                                referenceId,
                                message:
                                    "The reference belongs to another screen, possibly the original before cloning.",
                            });
                        }
                        if (options.expectFixedOverlays && !this.fixed(reference, shapes, fixed)) {
                            add({
                                severity: "warning",
                                code: "reference-not-fixed",
                                shape: source,
                                referenceId,
                                message: "Neither the reference nor its ancestors is fixed when scrolling.",
                            });
                        }
                    }
                } else if (overlay && !viewport && options.expectFixedOverlays) {
                    add({
                        severity: "warning",
                        code: "fixed-reference-unspecified",
                        shape: source,
                        message:
                            "Positioned against the whole base board; use relativeTo: 'viewport' to keep it on screen.",
                    });
                }
            }
        }

        for (const flow of page.flows) {
            const startId = flow.startingBoard?.id;
            const start = startId ? shapes.get(startId) : undefined;
            if (!start || start.type !== "board" || start.showInViewMode === false) {
                add({
                    severity: "error",
                    code: "flow-start-unavailable",
                    flow: this.label(flow.name),
                    destinationId: startId,
                    message: "The flow has no available starting board in View mode.",
                });
            }
        }
        const issues = [...errors, ...warnings].slice(0, limit);
        return {
            verification: "structure-only",
            viewerBehaviorVerified: false,
            apiVersion: context.version ?? null,
            file: { id: file.id, name: this.label(file.name) },
            page: { id: page.id, name: this.label(page.name) },
            summary: {
                shapes: all.length,
                boards: all.filter((shape) => shape.type === "board").length,
                flows: page.flows.length,
                interactions: interactionCount,
                actions: Object.fromEntries(actions),
                errors: errorCount,
                warnings: warningCount,
                issuesByCode: counts,
                returnedIssues: issues.length,
                omittedIssues: errorCount + warningCount - issues.length,
            },
            issues,
        };
    }
}
