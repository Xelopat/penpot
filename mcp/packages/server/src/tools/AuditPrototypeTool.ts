import { z } from "zod";
import type { ToolAnnotations } from "@modelcontextprotocol/sdk/types.js";
import { Tool } from "../Tool";
import { TextResponse, type ToolResponse } from "../ToolResponse";
import type { PenpotMcpServer } from "../PenpotMcpServer";
import type { PrototypeAuditOptions } from "../PrototypeAudit";
import { buildPrototypeAuditCode } from "../PrototypeAuditCode";
import { ExecuteCodePluginTask } from "../tasks/ExecuteCodePluginTask";

export class AuditPrototypeArgs implements PrototypeAuditOptions {
    static schema = {
        pageId: z
            .string()
            .min(1)
            .max(128)
            .optional()
            .describe("Page ID; defaults to the active page without switching pages."),
        maxIssues: z
            .number()
            .int()
            .min(0)
            .max(100)
            .default(20)
            .describe("Maximum issue details; totals always cover the full page. Use 0 for counts only."),
        sameWidthNavigation: z
            .boolean()
            .default(false)
            .describe(
                "Warn when navigate-to changes screen width. Enable only for flows expected to keep the same device width."
            ),
        expectFixedOverlays: z
            .boolean()
            .default(false)
            .describe("Warn about non-fixed or unspecified overlay references when fixed positioning is expected."),
    };
    pageId?: string;
    maxIssues?: number;
    sameWidthNavigation?: boolean;
    expectFixedOverlays?: boolean;
}

/** Compact read-only prototype inspection using the connected Plugin API. */
export class AuditPrototypeTool extends Tool<AuditPrototypeArgs> {
    constructor(server: PenpotMcpServer) {
        super(server, AuditPrototypeArgs.schema);
    }

    public getToolName(): string {
        return "audit_prototype";
    }

    public getToolDescription(): string {
        return (
            "Reads prototype structure and returns compact counts and bounded issue details. " +
            "Checks destinations, View-mode navigation, flow starts and overlay references, including stale references after cloning. " +
            "Reads the selected page without changing it or the design. " +
            "Does not click hotspots, play animations or verify visual centering in the viewer."
        );
    }

    public override getToolAnnotations(): ToolAnnotations {
        return { readOnlyHint: true, destructiveHint: false, idempotentHint: true, openWorldHint: false };
    }

    protected async executeCore(args: AuditPrototypeArgs): Promise<ToolResponse> {
        const task = new ExecuteCodePluginTask({ code: buildPrototypeAuditCode(args) });
        const result = await this.mcpServer.pluginBridge.executePluginTask(task);
        if (result.data?.result == null)
            throw new Error("Prototype audit returned no result from the connected editor.");
        return new TextResponse(JSON.stringify(result.data.result));
    }
}
