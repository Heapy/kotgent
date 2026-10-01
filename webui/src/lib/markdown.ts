import { fromMarkdown } from "mdast-util-from-markdown";
import { gfmFromMarkdown } from "mdast-util-gfm";
import { gfm } from "micromark-extension-gfm";
import type { Nodes } from "mdast";

export type MarkdownNode =
  | { type: "paragraph"; children: MarkdownNode[] }
  | { type: "heading"; depth: 1 | 2 | 3 | 4 | 5 | 6; children: MarkdownNode[] }
  | { type: "list"; ordered: boolean; start: number; children: MarkdownNode[] }
  | { type: "listItem"; checked: boolean | null; children: MarkdownNode[] }
  | { type: "table"; align: ("left" | "center" | "right" | null)[]; rows: MarkdownNode[][][] }
  | { type: "codeBlock"; text: string }
  | { type: "inlineCode"; text: string }
  | { type: "emphasis"; children: MarkdownNode[] }
  | { type: "strong"; children: MarkdownNode[] }
  | { type: "delete"; children: MarkdownNode[] }
  | { type: "link"; href: string; children: MarkdownNode[] }
  | { type: "text"; text: string }
  | { type: "blockquote"; children: MarkdownNode[] }
  | { type: "lineBreak" }
  | { type: "thematicBreak" };

function linkNodes(destination: string, children: MarkdownNode[], secureWww = false): MarkdownNode[] {
  const href = destination.trim();
  const folded = href.toLowerCase();
  if (!folded.startsWith("http://") && !folded.startsWith("https://")) return children;
  try {
    const url = new URL(href);
    if (secureWww) url.protocol = "https:";
    return [{ type: "link", href: url.href, children: children }];
  } catch {
    return children;
  }
}

function collectDefinitions(node: Nodes, definitions: Map<string, string>): void {
  if (node.type === "definition") {
    const identifier = node.identifier.toLowerCase();
    if (!definitions.has(identifier)) definitions.set(identifier, node.url);
  } else if ("children" in node) {
    for (const child of node.children) collectDefinitions(child, definitions);
  }
}

function convertNodes(nodes: readonly Nodes[], definitions: ReadonlyMap<string, string>, markdown: string): MarkdownNode[] {
  const result: MarkdownNode[] = [];
  for (const node of nodes) {
    for (const converted of convertNode(node, definitions, markdown)) {
      const previous = result.at(-1);
      if (previous?.type === "text" && converted.type === "text") previous.text += converted.text;
      else result.push(converted);
    }
  }
  return result;
}

function convertNode(node: Nodes, definitions: ReadonlyMap<string, string>, markdown: string): MarkdownNode[] {
  switch (node.type) {
    case "root":
      return convertNodes(node.children, definitions, markdown);
    case "paragraph":
    case "emphasis":
    case "strong":
    case "delete":
    case "blockquote":
      return [{ type: node.type, children: convertNodes(node.children, definitions, markdown) }];
    case "listItem":
      return [{ type: "listItem", checked: node.checked ?? null, children: convertNodes(node.children, definitions, markdown) }];
    case "table":
      return [{
        type: "table",
        align: node.align ?? [],
        rows: node.children.map(row => row.children.map(cell => convertNodes(cell.children, definitions, markdown))),
      }];
    case "heading":
      return [{ type: "heading", depth: node.depth, children: convertNodes(node.children, definitions, markdown) }];
    case "list":
      return [{ type: "list", ordered: node.ordered ?? false, start: node.start ?? 1, children: convertNodes(node.children, definitions, markdown) }];
    case "code":
      return [{ type: "codeBlock", text: node.value }];
    case "inlineCode":
    case "text":
      return [{ type: node.type, text: node.value }];
    case "link": {
      const start = node.position?.start.offset;
      const wwwLiteral = start !== undefined && markdown.slice(start, start + 4).toLowerCase() === "www.";
      return linkNodes(node.url, convertNodes(node.children, definitions, markdown), wwwLiteral);
    }
    case "linkReference":
      return linkNodes(definitions.get(node.identifier.toLowerCase()) ?? "", convertNodes(node.children, definitions, markdown));
    case "image":
      return linkNodes(node.url, [{ type: "text", text: node.alt || node.url }]);
    case "imageReference": {
      const href = definitions.get(node.identifier.toLowerCase()) ?? "";
      return linkNodes(href, [{ type: "text", text: node.alt || href }]);
    }
    case "break":
      return [{ type: "lineBreak" }];
    case "thematicBreak":
      return [{ type: "thematicBreak" }];
    case "footnoteReference":
      return [{ type: "text", text: "[^" + (node.label ?? node.identifier) + "]" }];
    case "footnoteDefinition":
      return [];
    case "html":
      // Agent tags and attributes must never cross into the renderable tree.
      return [];
    default:
      return [];
  }
}

export function parseMarkdown(markdown: string): MarkdownNode[] {
  const root = fromMarkdown(markdown, { extensions: [gfm()], mdastExtensions: [gfmFromMarkdown()] });
  const definitions = new Map<string, string>();
  collectDefinitions(root, definitions);
  return convertNodes(root.children, definitions, markdown);
}
