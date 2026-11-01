import { prototypeAuditBundle } from "./generated/PrototypeAuditBundle";
import type { PrototypeAuditOptions } from "./PrototypeAudit";

/** Browser-executable audit script with bounded, JSON-encoded arguments. */
export function buildPrototypeAuditCode(options: PrototypeAuditOptions): string {
    return `${prototypeAuditBundle}\nreturn PenpotPrototypeAudit.PrototypeAudit.run(penpot, ${JSON.stringify(options)});`;
}

/** Existing execute_code compatibility without adding variables to user code. */
export function withPrototypeAuditHelper(code: string): string {
    return `((utils, api) => {
${prototypeAuditBundle}
Object.defineProperty(utils, "auditPrototype", {
    configurable: true,
    value: (options = {}) => PenpotPrototypeAudit.PrototypeAudit.run(api, options)
});
})(penpotUtils, penpot);
${code}`;
}
