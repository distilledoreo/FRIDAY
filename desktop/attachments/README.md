# Document attachments on the desktop API

The Android app sends file bytes to `POST /extract` on its configured search/voice service, with that service's bearer token. The endpoint is already deployed on the author's desktop assistant API. `extract.py` preserves that implementation so it can be reviewed and reused with the app.

For an existing FastAPI service, install `requirements.txt`, import the module, then call `install_document_extraction(app, auth_dependencies)` using the same dependency list that authenticates the other endpoints. Keep the service's existing bind address and gateway configuration.

The request uses the file's MIME type as `Content-Type` and a percent-encoded filename as `X-Filename`. The response includes `name`, `kind`, `text`, `chars`, `truncated`, and, for PDFs, `pages` and `images` (base64 JPEG pages). `note` describes scan-page limits. The Android importer keeps the extracted text and page images with the chat.

Limits: 25 MB input, 60,000 text characters, four scanned-page images. Supports PDF, DOCX, and text/code formats. Invalid files return 422; unsupported binary formats return 415. Model image input is separate: the OpenAI-compatible model endpoint must support `image_url` content.

Verification used generated PDF text, image-only PDFs, DOCX tables, CSV, long text, and an unsupported binary file through the deployed authenticated endpoint. No personal files were used.
