import { describe, test } from "node:test";
import assert from "node:assert/strict";

import { parseMarkdown } from "../../webui/src/lib/markdown.ts";
import type { MarkdownNode } from "../../webui/src/lib/markdown.ts";

const text = (value: string): MarkdownNode => ({ type: "text", text: value });
const paragraph = (...children: MarkdownNode[]): MarkdownNode => ({ type: "paragraph", children: children });
const link = (href: string, ...children: MarkdownNode[]): MarkdownNode => ({ type: "link", href: href, children: children });

describe("parseMarkdown raw HTML", () => {
  test("drops script blocks including their contents between safe paragraphs", () => {
    assert.deepEqual(parseMarkdown('Before.\n\n<script>\nalert("x")\n</script>\n\nAfter.'), [
      paragraph(text("Before.")),
      paragraph(text("After.")),
    ]);
  });

  test("drops inline script tags and keeps their contents as ordinary text", () => {
    assert.deepEqual(parseMarkdown('Before <script>alert("x")</script> after.'), [
      paragraph(text('Before alert("x") after.')),
    ]);
  });

  test("drops an HTML image with an event handler", () => {
    assert.deepEqual(parseMarkdown("<img src=x onerror=alert(1)>"), []);
    assert.deepEqual(parseMarkdown("Before <img src=x onerror=alert(1)> after."), [
      paragraph(text("Before  after.")),
    ]);
  });

  test("drops raw anchors and their javascript attributes", () => {
    assert.deepEqual(parseMarkdown('Before <a href="javascript:alert(1)">x</a> after.'), [
      paragraph(text("Before x after.")),
    ]);
  });

  test("drops other HTML blocks and inline tags", () => {
    assert.deepEqual(parseMarkdown('<iframe src="https://example.com/"></iframe>'), []);
    assert.deepEqual(parseMarkdown('Before <svg onload="alert(1)"></svg> after.'), [
      paragraph(text("Before  after.")),
    ]);
  });

  test("keeps escaped HTML and decoded entities as plain text", () => {
    assert.deepEqual(parseMarkdown("&lt;script&gt;alert(1)&lt;/script&gt; &amp; \\<img>"), [
      paragraph(text("<script>alert(1)</script> & <img>")),
    ]);
  });
});

describe("parseMarkdown links", () => {
  const rejected = [
    "[x](javascript:alert(1))",
    "[x](JaVaScRiPt:alert(1))",
    "[x]( javascript:alert(1))",
    "[x](data:text/html;base64,PHNjcmlwdD4=)",
    "[x](DaTa:text/html,alert(1))",
    "[x](vbscript:msgbox(1))",
    "[x](mailto:agent@example.com)",
    "[x](/api/v1/session)",
    "[x](//example.com/path)",
    "[x](./file)",
    "[x](#fragment)",
    "[x](https:relative)",
    "[x](https://)",
    "[x](java&#x73;cript&#x3a;alert(1))",
    "[x](java&#x09;script:alert(1))",
  ];

  for (const markdown of rejected) {
    test("renders a rejected destination as its label: " + markdown, () => {
      assert.deepEqual(parseMarkdown(markdown), [paragraph(text("x"))]);
    });
  }

  test("allows HTTP and HTTPS with case folding and surrounding whitespace", () => {
    assert.deepEqual(parseMarkdown("[http](http://example.com/path) [https]( \tHTTPS://Example.COM/path?x=1&y=2 \t)"), [
      paragraph(
        link("http://example.com/path", text("http")),
        text(" "),
        link("https://example.com/path?x=1&y=2", text("https")),
      ),
    ]);
  });

  test("preserves label formatting when rejecting a link", () => {
    assert.deepEqual(parseMarkdown("[**x**](javascript:alert(1))"), [
      paragraph({ type: "strong", children: [text("x")] }),
    ]);
  });

  test("allows HTTP(S) autolinks", () => {
    assert.deepEqual(parseMarkdown("<https://example.com/path> <HTTP://example.com/path>"), [
      paragraph(
        link("https://example.com/path", text("https://example.com/path")),
        text(" "),
        link("http://example.com/path", text("HTTP://example.com/path")),
      ),
    ]);
  });

  for (const destination of ["javascript:alert(1)", "JaVaScRiPt:alert(1)", "data:text/html,alert(1)", "vbscript:msgbox(1)"]) {
    test("rejects executable autolinks: " + destination, () => {
      assert.deepEqual(parseMarkdown("<" + destination + ">"), [paragraph(text(destination))]);
    });
  }

  test("renders email autolinks as text", () => {
    assert.deepEqual(parseMarkdown("<agent@example.com>"), [paragraph(text("agent@example.com"))]);
  });

  test("applies the allowlist to reference links", () => {
    assert.deepEqual(parseMarkdown("[safe][PLAN] [unsafe][bad]\n\n[plan]: https://example.com/plan\n[bad]: javascript:alert(1)"), [
      paragraph(link("https://example.com/plan", text("safe")), text(" unsafe")),
    ]);
  });
});

describe("parseMarkdown images", () => {
  test("turns an image into a text link without image or title attributes", () => {
    assert.deepEqual(parseMarkdown('![diagram](https://example.com/diagram.png "ignored")'), [
      paragraph(link("https://example.com/diagram.png", text("diagram"))),
    ]);
  });

  test("uses the URL as text when an image has no label", () => {
    assert.deepEqual(parseMarkdown("![](https://example.com/diagram.png)"), [
      paragraph(link("https://example.com/diagram.png", text("https://example.com/diagram.png"))),
    ]);
  });

  for (const destination of ["javascript:alert(1)", "data:image/svg+xml,evil", "/private/image.png", "//example.com/image.png"]) {
    test("renders an image with a rejected destination as plain text: " + destination, () => {
      assert.deepEqual(parseMarkdown("![diagram](" + destination + ")"), [paragraph(text("diagram"))]);
    });
  }

  test("turns reference images into text links", () => {
    assert.deepEqual(parseMarkdown("![diagram][image]\n\n[image]: https://example.com/image.png"), [
      paragraph(link("https://example.com/image.png", text("diagram"))),
    ]);
  });
});

describe("parseMarkdown structure", () => {
  test("preserves nested unordered and ordered lists and their starting number", () => {
    assert.deepEqual(parseMarkdown("- parent\n\n  3. child\n     - leaf\n- sibling"), [{
      type: "list",
      ordered: false,
      start: 1,
      children: [
        { type: "listItem", children: [
          paragraph(text("parent")),
          { type: "list", ordered: true, start: 3, children: [
            { type: "listItem", children: [
              paragraph(text("child")),
              { type: "list", ordered: false, start: 1, children: [
                { type: "listItem", children: [paragraph(text("leaf"))] },
              ] },
            ] },
          ] },
        ] },
        { type: "listItem", children: [paragraph(text("sibling"))] },
      ],
    }]);
  });

  test("keeps HTML in fenced code as literal code text", () => {
    const code = '<script>alert(1)</script>\n<img src=x onerror="alert(2)">';
    assert.deepEqual(parseMarkdown("```html\n" + code + "\n```"), [{ type: "codeBlock", text: code }]);
  });

  test("keeps HTML in indented code and inline code as literal code text", () => {
    const code = '<a href="javascript:alert(1)">x</a>';
    assert.deepEqual(parseMarkdown("    " + code), [{ type: "codeBlock", text: code }]);
    assert.deepEqual(parseMarkdown("`" + code + "`"), [paragraph({ type: "inlineCode", text: code })]);
  });

  for (const depth of [1, 2, 3, 4, 5, 6] as const) {
    test("preserves heading level " + depth, () => {
      assert.deepEqual(parseMarkdown("#".repeat(depth) + " Heading"), [
        { type: "heading", depth: depth, children: [text("Heading")] },
      ]);
    });
  }

  test("preserves setext headings", () => {
    assert.deepEqual(parseMarkdown("Heading\n======="), [
      { type: "heading", depth: 1, children: [text("Heading")] },
    ]);
  });

  test("preserves nested emphasis and strong text", () => {
    assert.deepEqual(parseMarkdown("plain *em **strong** em*"), [
      paragraph(text("plain "), {
        type: "emphasis",
        children: [text("em "), { type: "strong", children: [text("strong")] }, text(" em")],
      }),
    ]);
  });

  test("preserves blockquotes, hard line breaks, soft newlines and thematic breaks", () => {
    assert.deepEqual(parseMarkdown("> one  \n> two\\\n> three\n> four\n\n---"), [
      { type: "blockquote", children: [paragraph(
        text("one"), { type: "lineBreak" }, text("two"), { type: "lineBreak" }, text("three\nfour"),
      )] },
      { type: "thematicBreak" },
    ]);
  });

  test("returns an empty tree for empty markdown", () => {
    assert.deepEqual(parseMarkdown(""), []);
  });
});
