# K2 Horizon 0.9B MNN

Default bundle: XYETHER/K2-Horizon-0.9B-MNN, pinned revision d0c9d33caf992e399ff934b0d535933ce9d6bf9e. Six downloaded runtime files total1,241,023,014bytes, INT8 weights/BF16 embeddings. Individual sizes/SHA256 are in LocalModelCatalog.kt. Original HF tokenizer is included in app assets; both input encoding and token-ID output decoding use K2Tokenizer rather than stock MNN text decoding.

Original checkpoint: IFM/K2-Horizon-0.9B, revision76db896cfa459b82c71998a7741e30b54b37ec7f. Converted using MNN3.6.1 exporter commit d407447ed56c4121a11ccbd266dc184ca1ead0c2 with explicit K2 architecture mapping. Original tokenizer and model are Apache-2.0. Weights are downloaded separately; no weights are included in this GitHub repository/APK.

Historical S24 Ultra short128-token CPU sample:33.60tok/s; GPU33.55tok/s but arithmetic differed, so CPU remains recommended. These are short-prompt observations, not sustained/full-context measurements. Tool reliability and complex HTML generation remain limited.
