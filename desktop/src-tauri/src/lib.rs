// A light native window for the Agentic OS dashboard: the bundled page forwards to http://127.0.0.1:8810
// as soon as the OS answers, and says how to start it when it doesn't.
#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
  tauri::Builder::default()
    .run(tauri::generate_context!())
    .expect("error while running Agentic OS");
}
