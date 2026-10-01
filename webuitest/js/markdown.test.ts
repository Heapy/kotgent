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

describe("parseMarkdown GFM", () => {
  test("preserves table alignment and inline content with the first row as the header", () => {
    assert.deepEqual(parseMarkdown(
      "| **Plan** | `Step` | Center | Right |\n" +
      "| --- | :--- | :---: | ---: |\n" +
      "| *Work* | [guide](https://example.com/guide) | ~~old~~ | ![diagram](https://example.com/diagram.png) |",
    ), [{
      type: "table",
      align: [null, "left", "center", "right"],
      rows: [
        [
          [{ type: "strong", children: [text("Plan")] }],
          [{ type: "inlineCode", text: "Step" }],
          [text("Center")],
          [text("Right")],
        ],
        [
          [{ type: "emphasis", children: [text("Work")] }],
          [link("https://example.com/guide", text("guide"))],
          [{ type: "delete", children: [text("old")] }],
          [link("https://example.com/diagram.png", text("diagram"))],
        ],
      ],
    }]);
  });

  test("preserves a header-only table", () => {
    assert.deepEqual(parseMarkdown("| Plan | Status |\n| --- | --- |"), [{
      type: "table",
      align: [null, null],
      rows: [[[text("Plan")], [text("Status")]]],
    }]);
  });

  test("distinguishes checked, unchecked and ordinary list items", () => {
    assert.deepEqual(parseMarkdown("- [x] **Done**\n- [ ] Pending\n- [X] Also done\n- Ordinary"), [{
      type: "list",
      ordered: false,
      start: 1,
      children: [
        { type: "listItem", checked: true, children: [paragraph({ type: "strong", children: [text("Done")] })] },
        { type: "listItem", checked: false, children: [paragraph(text("Pending"))] },
        { type: "listItem", checked: true, children: [paragraph(text("Also done"))] },
        { type: "listItem", checked: null, children: [paragraph(text("Ordinary"))] },
      ],
    }]);
  });

  test("preserves nested tasks in ordered lists", () => {
    assert.deepEqual(parseMarkdown("3. [ ] Parent\n   - [x] Child"), [{
      type: "list",
      ordered: true,
      start: 3,
      children: [{ type: "listItem", checked: false, children: [
        paragraph(text("Parent")),
        { type: "list", ordered: false, start: 1, children: [
          { type: "listItem", checked: true, children: [paragraph(text("Child"))] },
        ] },
      ] }],
    }]);
  });

  test("preserves strikethrough with inline formatting", () => {
    assert.deepEqual(parseMarkdown("Use ~~the **old** `plan`~~ instead."), [
      paragraph(text("Use "), { type: "delete", children: [
        text("the "), { type: "strong", children: [text("old")] }, text(" "), { type: "inlineCode", text: "plan" },
      ] }, text(" instead.")),
    ]);
  });

  test("validates HTTP(S) autolink literals and upgrades parser-provided www URLs to HTTPS", () => {
    assert.deepEqual(parseMarkdown("http://example.com/plan https://example.com/guide www.example.com/notes"), [
      paragraph(
        link("http://example.com/plan", text("http://example.com/plan")),
        text(" "),
        link("https://example.com/guide", text("https://example.com/guide")),
        text(" "),
        link("https://www.example.com/notes", text("www.example.com/notes")),
      ),
    ]);
  });

  test("keeps javascript lookalikes and email literals as plain text", () => {
    assert.deepEqual(parseMarkdown("javascript:alert(1) JaVaScRiPt:alert(2) agent@example.com"), [
      paragraph(text("javascript:alert(1) JaVaScRiPt:alert(2) agent@example.com")),
    ]);
  });

  test("keeps invalid HTTP(S) and www literal URLs as plain text", () => {
    const markdown = "https://example.com:999999/plan www.example.com:999999/plan";
    assert.deepEqual(parseMarkdown(markdown), [paragraph(text(markdown))]);
  });

  test("preserves explicit destinations with www labels", () => {
    assert.deepEqual(parseMarkdown("[www.example.com/plan](http://www.example.com/plan) [www.example.com](javascript:alert(1))"), [
      paragraph(link("http://www.example.com/plan", text("www.example.com/plan")), text(" www.example.com")),
    ]);
  });

  test("drops HTML and rejects unsafe links inside table cells", () => {
    assert.deepEqual(parseMarkdown(
      "| Before <img src=x onerror=alert(1)> after | <script>alert(2)</script> |\n" +
      "| --- | --- |\n" +
      '| <a href="javascript:alert(3)">label</a> | [unsafe](javascript:alert(4)) |',
    ), [{
      type: "table",
      align: [null, null],
      rows: [
        [[text("Before  after")], [text("alert(2)")]],
        [[text("label")], [text("unsafe")]],
      ],
    }]);
  });

  test("keeps footnote references as plain text and drops definitions", () => {
    assert.deepEqual(parseMarkdown('Plan[^note].\n\n[^note]: Hidden **definition** <img src=x onerror=alert(1)>\n\nAfter.'), [
      paragraph(text("Plan[^note].")),
      paragraph(text("After.")),
    ]);
    assert.deepEqual(parseMarkdown("[^note]: Hidden definition"), []);
    assert.deepEqual(parseMarkdown("Unknown[^missing]."), [paragraph(text("Unknown[^missing]."))]);
  });
});

describe("parseMarkdown structure", () => {
  test("preserves nested unordered and ordered lists and their starting number", () => {
    assert.deepEqual(parseMarkdown("- parent\n\n  3. child\n     - leaf\n- sibling"), [{
      type: "list",
      ordered: false,
      start: 1,
      children: [
        { type: "listItem", checked: null, children: [
          paragraph(text("parent")),
          { type: "list", ordered: true, start: 3, children: [
            { type: "listItem", checked: null, children: [
              paragraph(text("child")),
              { type: "list", ordered: false, start: 1, children: [
                { type: "listItem", checked: null, children: [paragraph(text("leaf"))] },
              ] },
            ] },
          ] },
        ] },
        { type: "listItem", checked: null, children: [paragraph(text("sibling"))] },
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
