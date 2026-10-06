# Privacy

Open Horizon runs model inference on your phone. Chats, settings, downloaded models and workspace files are stored in the app’s private storage. The agent’s file tools are restricted to its workspace.

Model downloads contact Hugging Face. Web search sends your search query to the selected search service; reading a page contacts that website. Relevant fetched text can be included in local model context. These services have their own privacy policies. Speech input, when used, depends on your Android speech provider.

This fork’s chat inference uses the local K2 GGUF model; it has no custom cloud-model setup. No analytics service is configured. Uninstalling the app removes its private data. Explicit model deletion removes downloaded model files. Keep exported files and app data you need before uninstalling.

