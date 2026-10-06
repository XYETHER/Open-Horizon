---
name: html-page
description: Create and verify a single offline mobile HTML page in the agent folder.
category: coding
---

Create the requested page using write_file, not just chat code. Default path single.html; use full HTML with doctype, charset, viewport and inline CSS. No remote assets, shell or npm. Keep code compact. Read the file back; browser_navigate uses url=single.html, then browser_get_dom and browser_get_logs use {}. Fix actual errors. Finish Done and path; View HTML opens it.
