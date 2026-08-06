# Security Policy

## Supported Versions

Gonzalez does not yet maintain parallel maintenance branches. Security fixes
are made against the latest release and the `main` branch; older releases
are not backported.

| Version | Supported          |
| ------- | ------------------ |
| 1.2.x   | :white_check_mark: |
| < 1.2   | :x:                |

## Reporting a Vulnerability

Please **do not** open a public GitHub issue for security vulnerabilities.

The preferred way to report a vulnerability is through GitHub's private
vulnerability reporting:

1. Go to the [Security tab](https://github.com/cpkb-bluezoo/gonzalez/security) of this repository.
2. Click **Report a vulnerability**.
3. Provide as much detail as possible: affected version(s), a minimal
   reproduction (ideally a small XML document and the API call that
   triggers the issue), and the observed impact.

If you are unable to use GitHub's private reporting, you may instead email
the maintainer directly at **dog@gnu.org**. Please include "Gonzalez
security" in the subject line, and PGP-encrypt the report if it contains
proof-of-concept exploit code.

You should expect an initial response within a few days acknowledging
receipt. Gonzalez is currently maintained by a single volunteer maintainer,
so please be patient — a fix timeline will be communicated once the report
has been triaged, and credit will be given in the release notes unless you
ask to remain anonymous. Coordinated disclosure is appreciated: please give
the maintainer a reasonable window to publish a fix before disclosing
publicly.

## Scope

Gonzalez is an XML parser and serializer, so reports of particular interest
include (but are not limited to):

- Parser crashes, hangs, or excessive memory/CPU consumption on malformed
  or adversarial input (denial of service)
- Entity-expansion amplification ("billion laughs" and similar) bypassing
  the built-in expansion limits
- XML External Entity (XXE) issues — unintended file or network access via
  external DTD subsets or entities, including when the caller has not
  explicitly opted in to external entity resolution
- Incorrect well-formedness/validation results that could lead a consuming
  application to trust unsafe input
- Any memory-safety or arbitrary-code-execution issue

Reports about the XSLT/XPath modules, or about the sample/standalone test
harnesses under `simpleparse`/`simpletransform`, are also welcome, though
they are lower priority than the core parser/serializer.
