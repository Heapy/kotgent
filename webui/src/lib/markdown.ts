import { fromMarkdown } from "mdast-util-from-markdown";
import type { Nodes } from "mdast";

export type MarkdownNode =
  | { type: "paragraph"; children: MarkdownNode[] }
  | { type: "heading"; depth: 1 | 2 | 3 | 4 | 5 | 6; children: MarkdownNode[] }
  | { type: "list"; ordered: boolean; start: number; children: MarkdownNode[] }
  | { type: "listItem"; children: MarkdownNode[] }
  | { type: "codeBlock"; text: string }
  | { type: "inlineCode"; text: string }
  | { type: "emphasis"; children: MarkdownNode[] }
  | { type: "strong"; children: MarkdownNode[] }
  | { type: "link"; href: string; children: MarkdownNode[] }
  | { type: "text"; text: string }
  | { type: "blockquote"; children: MarkdownNode[] }
  | { type: "lineBreak" }
  | { type: "thematicBreak" };

function linkNodes(destination: string, children: MarkdownNode[]): MarkdownNode[] {
  const href = destination.trim();
  const folded = href.toLowerCase();
  if (!folded.startsWith("http://") && !folded.startsWith("https://")) return children;
  try {
    return [{ type: "link", href: new URL(href).href, children: children }];
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

function convertNodes(nodes: readonly Nodes[], definitions: ReadonlyMap<string, string>): MarkdownNode[] {
  const result: MarkdownNode[] = [];
  for (const node of nodes) {
    for (const converted of convertNode(node, definitions)) {
      const previous = result.at(-1);
      if (previous?.type === "text" && converted.type === "text") previous.text += converted.text;
      else result.push(converted);
    }
  }
  return result;
}

function convertNode(node: Nodes, definitions: ReadonlyMap<string, string>): MarkdownNode[] {
  switch (node.type) {
    case "root":
      return convertNodes(node.children, definitions);
    case "paragraph":
    case "listItem":
    case "emphasis":
    case "strong":
    case "blockquote":
      return [{ type: node.type, children: convertNodes(node.children, definitions) }];
    case "heading":
      return [{ type: "heading", depth: node.depth, children: convertNodes(node.children, definitions) }];
    case "list":
      return [{ type: "list", ordered: node.ordered ?? false, start: node.start ?? 1, children: convertNodes(node.children, definitions) }];
    case "code":
      return [{ type: "codeBlock", text: node.value }];
    case "inlineCode":
    case "text":
      return [{ type: node.type, text: node.value }];
    case "link":
      return linkNodes(node.url, convertNodes(node.children, definitions));
    case "linkReference":
      return linkNodes(definitions.get(node.identifier.toLowerCase()) ?? "", convertNodes(node.children, definitions));
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
    case "html":
      // Agent tags and attributes must never cross into the renderable tree.
      return [];
    default:
      return [];
  }
}

export function parseMarkdown(markdown: string): MarkdownNode[] {
  const root = fromMarkdown(markdown);
  const definitions = new Map<string, string>();
  collectDefinitions(root, definitions);
  return convertNodes(root.children, definitions);
}
