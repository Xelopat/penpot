import assert from "node:assert/strict";
import test from "node:test";
import { z } from "zod";
import { PrototypeAudit, type AuditShape, type AuditPage, type AuditContext } from "./PrototypeAudit";
import { buildPrototypeAuditCode, withPrototypeAuditHelper } from "./PrototypeAuditCode";
import { AuditPrototypeTool, AuditPrototypeArgs } from "./tools/AuditPrototypeTool";
import type { PenpotMcpServer } from "./PenpotMcpServer";
import type { ExecuteCodePluginTask } from "./tasks/ExecuteCodePluginTask";

type Shape = { -readonly [Key in keyof AuditShape]: AuditShape[Key] };

function shape(id: string, type: string, parent: AuditShape | null, width = 1440): Shape {
    return { id, name: id, type, parent, width, interactions: [], showInViewMode: true };
}

function fixture() {
    const root = shape("root", "board", null);
    const a = shape("screen-a", "board", root);
    const b = shape("screen-b", "board", root);
    const modal = shape("modal", "board", root, 480);
    modal.showInViewMode = false;
    const reference = shape("viewport", "board", a);
    reference.fixedWhenScrolling = true;
    const trigger = shape("button", "rectangle", a);
    trigger.interactions = [
        { action: { type: "open-overlay", destination: modal, relativeTo: reference } },
        { action: { type: "navigate-to", destination: b } },
    ];
    const shapes = [a, b, modal, reference, trigger];
    let reads = 0;
    const page: AuditPage = {
        id: "page",
        name: "Page",
        root,
        flows: [{ name: "Start", startingBoard: a }],
        findShapes: () => {
            reads++;
            return shapes;
        },
    };
    const context: AuditContext = {
        version: "2.18.5",
        currentPage: page,
        currentFile: { id: "file", name: "File", pages: [page] },
    };
    return { context, page, root, a, b, modal, reference, trigger, shapes, reads: () => reads };
}

test("valid prototype is read without mutations or a full tree response", () => {
    const f = fixture();
    f.shapes.forEach(Object.freeze);
    Object.freeze(f.shapes);
    Object.freeze(f.context);
    const result = PrototypeAudit.run(f.context, { expectFixedOverlays: true, sameWidthNavigation: true });
    assert.equal(f.reads(), 1);
    assert.equal(result.summary.errors, 0);
    assert.equal(result.summary.warnings, 0);
    assert.equal(result.summary.interactions, 2);
    assert.equal(result.summary.shapes, 5);
    assert.equal(result.viewerBehaviorVerified, false);
    assert.equal(result.verification, "structure-only");
    assert.equal(JSON.stringify(result).includes("children"), false);
});

test("missing, non-board and excluded navigation destinations are reported", () => {
    const f = fixture();
    f.b.showInViewMode = false;
    f.trigger.interactions = [
        { action: { type: "navigate-to" } },
        { action: { type: "open-overlay", destination: shape("gone", "board", f.root) } },
        { action: { type: "navigate-to", destination: f.trigger } },
        { action: { type: "navigate-to", destination: f.b } },
        { action: { type: "close-overlay" } },
    ];
    const result = PrototypeAudit.run(f.context);
    assert.equal(result.summary.errors, 4);
    assert.deepEqual(result.summary.issuesByCode, {
        "destination-missing": 2,
        "destination-not-board": 1,
        "destination-excluded-from-view": 1,
    });
});

test("an empty reference proxy is distinct from automatic positioning", () => {
    const f = fixture();
    f.trigger.interactions = [
        { action: { type: "open-overlay", destination: f.modal, relativeTo: shape("", "board", null) } },
        { action: { type: "toggle-overlay", destination: f.modal } },
    ];
    const result = PrototypeAudit.run(f.context);
    assert.equal(result.summary.errors, 1);
    assert.equal(result.summary.warnings, 0);
    assert.equal(result.issues[0].code, "reference-missing");
});

test("cloned triggers retaining the original reference are identified", () => {
    const f = fixture();
    f.trigger.parent = f.b;
    f.trigger.interactions = [{ action: { type: "open-overlay", destination: f.modal, relativeTo: f.reference } }];
    const result = PrototypeAudit.run(f.context);
    assert.equal(result.summary.warnings, 1);
    assert.equal(result.issues[0].code, "reference-outside-screen");
    assert.equal(result.issues[0].referenceId, f.reference.id);
});

test("fixed ancestors count as fixed overlay references", () => {
    const f = fixture();
    f.a.fixedWhenScrolling = true;
    f.reference.fixedWhenScrolling = false;
    const result = PrototypeAudit.run(f.context, { expectFixedOverlays: true });
    assert.equal(result.summary.warnings, 0);
});

test("returning from an overlay to a full screen is not a device-width warning", () => {
    const f = fixture();
    f.reference.interactions = [{ action: { type: "open-overlay", destination: f.modal, relativeTo: f.reference } }];
    f.trigger.parent = f.modal;
    f.trigger.interactions = [{ action: { type: "navigate-to", destination: f.b } }];
    const result = PrototypeAudit.run(f.context, { sameWidthNavigation: true });
    assert.equal(result.summary.warnings, 0);
});

test("fixed overlays and same-width navigation are opt-in expectations", () => {
    const f = fixture();
    f.b.width = 390;
    f.reference.fixedWhenScrolling = false;
    assert.equal(PrototypeAudit.run(f.context).summary.warnings, 0);
    const result = PrototypeAudit.run(f.context, { expectFixedOverlays: true, sameWidthNavigation: true });
    assert.deepEqual(result.summary.issuesByCode, { "reference-not-fixed": 1, "navigation-width-mismatch": 1 });
    f.trigger.interactions = [{ action: { type: "open-overlay", destination: f.modal } }];
    assert.equal(
        PrototypeAudit.run(f.context, { expectFixedOverlays: true }).issues[0].code,
        "fixed-reference-unspecified"
    );
});

test("issue limits retain full counts and prioritize errors", () => {
    const f = fixture();
    f.b.width = 390;
    f.trigger.name = "x".repeat(10000);
    f.trigger.interactions = [
        ...Array.from({ length: 8 }, () => ({ action: { type: "navigate-to", destination: f.b } })),
        { action: { type: "navigate-to" } },
    ];
    const result = PrototypeAudit.run(f.context, { maxIssues: 2, sameWidthNavigation: true });
    assert.equal(result.summary.errors, 1);
    assert.equal(result.summary.warnings, 8);
    assert.equal(result.summary.omittedIssues, 7);
    assert.equal(result.issues[0].severity, "error");
    assert.equal(result.issues[0].shape?.name.length, 96);
    const counts = PrototypeAudit.run(f.context, { maxIssues: 0, sameWidthNavigation: true });
    assert.equal(counts.issues.length, 0);
    assert.equal(counts.summary.omittedIssues, 9);
});

test("an explicit page is inspected without switching the editor page", () => {
    const f = fixture();
    const other = { ...f.page, id: "other", name: "Other" };
    const context = { ...f.context, currentFile: { ...f.context.currentFile!, pages: [f.page, other] } };
    const result = PrototypeAudit.run(context, { pageId: "other" });
    assert.equal(result.page.id, "other");
    assert.equal(context.currentPage!.id, "page");
});

test("invalid flow starts are detected", () => {
    const f = fixture();
    const context = { ...f.context, currentPage: { ...f.page, flows: [{ name: "Broken" }] } };
    const result = PrototypeAudit.run(context);
    assert.equal(result.issues[0].code, "flow-start-unavailable");
});

test("invalid contexts, limits and parent cycles do not produce a false clean result", () => {
    const f = fixture();
    assert.throws(() => PrototypeAudit.run({ ...f.context, currentFile: null }), /Open a Penpot file/);
    assert.throws(() => PrototypeAudit.run(f.context, { pageId: "unknown" }), /requested page/);
    assert.throws(() => PrototypeAudit.run(f.context, { maxIssues: 101 }), /maxIssues/);
    f.trigger.parent = f.trigger;
    assert.equal(PrototypeAudit.run(f.context).summary.issuesByCode["source-outside-screen"], 1);
});

test("the serialized browser script is self-contained and escapes input", () => {
    const f = fixture();
    const options = { expectFixedOverlays: true, sameWidthNavigation: true };
    const execute = new Function("penpot", buildPrototypeAuditCode(options));
    assert.deepEqual(execute(f.context), PrototypeAudit.run(f.context, options));
    const pageId = 'page"); throw new Error("injected"); //';
    const special = { ...f.page, id: pageId };
    const context = { ...f.context, currentFile: { ...f.context.currentFile!, pages: [special] } };
    assert.equal(new Function("penpot", buildPrototypeAuditCode({ pageId }))(context).page.id, pageId);
});

test("the tool forwards a read-only audit and returns only its compact result", async () => {
    const f = fixture();
    const server = {
        pluginBridge: {
            executePluginTask: async (task: ExecuteCodePluginTask) => ({
                data: { result: new Function("penpot", task.params.code)(f.context), log: "unrelated log" },
            }),
        },
    } as unknown as PenpotMcpServer;
    const tool = new AuditPrototypeTool(server);
    const response = await tool.execute({ maxIssues: 0 });
    const item = response.content[0];
    assert.equal(item.type, "text");
    if (item.type !== "text") throw new Error("Expected text");
    assert.equal(JSON.parse(item.text).summary.errors, 0);
    assert.equal(item.text.includes("unrelated log"), false);
    assert.equal(tool.getToolAnnotations().readOnlyHint, true);
    assert.equal(tool.getToolAnnotations().destructiveHint, false);
    const schema = z.object(AuditPrototypeArgs.schema);
    assert.equal(schema.parse({}).maxIssues, 20);
    assert.equal(schema.safeParse({ maxIssues: -1 }).success, false);
});

test("execute_code compatibility preserves user scope, return values and storage", () => {
    const f = fixture();
    const utils = {};
    const storage = { userData: "preserved" };
    const run = new Function(
        "penpot",
        "penpotUtils",
        "storage",
        withPrototypeAuditHelper(
            "const PenpotPrototypeAudit = 42; return {value:PenpotPrototypeAudit, data:storage.userData, audit:penpotUtils.auditPrototype()};"
        )
    );
    const result = run(f.context, utils, storage);
    assert.equal(result.value, 42);
    assert.equal(result.data, "preserved");
    assert.equal(result.audit.summary.errors, 0);
    assert.deepEqual(Object.keys(storage), ["userData"]);
    assert.deepEqual(Object.keys(utils), []);
    assert.equal(new Function("penpot", "penpotUtils", withPrototypeAuditHelper("return 7;"))(f.context, utils), 7);
});
