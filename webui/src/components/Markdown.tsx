import type { ComponentChildren } from "preact";
import { parseMarkdown } from "../lib/markdown.ts";
import type { MarkdownNode } from "../lib/markdown.ts";

function renderNode(node: MarkdownNode): ComponentChildren {
  switch (node.type) {
    case "paragraph":
      return <p>{node.children.map(renderNode)}</p>;
    case "heading": {
      const children = node.children.map(renderNode);
      switch (node.depth) {
        case 1: return <h1>{children}</h1>;
        case 2: return <h2>{children}</h2>;
        case 3: return <h3>{children}</h3>;
        case 4: return <h4>{children}</h4>;
        case 5: return <h5>{children}</h5>;
        case 6: return <h6>{children}</h6>;
      }
    }
    case "list":
      return node.ordered
        ? <ol start={node.start}>{node.children.map(renderNode)}</ol>
        : <ul>{node.children.map(renderNode)}</ul>;
    case "listItem":
      return <li>{node.children.map(renderNode)}</li>;
    case "codeBlock":
      return <pre><code>{node.text}</code></pre>;
    case "inlineCode":
      return <code>{node.text}</code>;
    case "emphasis":
      return <em>{node.children.map(renderNode)}</em>;
    case "strong":
      return <strong>{node.children.map(renderNode)}</strong>;
    case "link":
      return <a href={node.href} rel="noopener noreferrer" target="_blank">{node.children.map(renderNode)}</a>;
    case "text":
      return node.text;
    case "blockquote":
      return <blockquote>{node.children.map(renderNode)}</blockquote>;
    case "lineBreak":
      return <br />;
    case "thematicBreak":
      return <hr />;
  }
}

export function Markdown({ text }: { text: string }) {
  return <>{parseMarkdown(text).map(renderNode)}</>;
}
