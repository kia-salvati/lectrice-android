//! Laptop smoke test of the whole path: import an invented PDF, hand the document over, speak one sentence.
//! Needs the Kokoro model and the desktop pdfium from the sibling `lectrice` checkout (or `LECTRICE_MODEL_DIR` and
//! `LECTRICE_PDFIUM`), so it is ignored by default: `cargo test -p lectrice-ffi -- --ignored`.

use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};

use lectrice_ffi::{CoreConfig, EngineConfig, EngineStatus, KokoroVariant, LectriceCore, LogLevel, LogSink, SpeakArgs};

const PAGES: [&[(u32, &str)]; 2] = [
    &[
        (24, "A Small Field Guide to Paper Boats"),
        (12, "Folding a paper boat takes one sheet and about two minutes of patience."),
        (12, "Start with a rectangle, fold it in half, and press the crease flat with a thumb."),
        (12, "The hull holds its shape best when every fold is sharp and even."),
    ],
    &[
        (12, "On still water a well folded boat can float for an hour or more."),
        (12, "Wax crayon rubbed on the bottom keeps the paper dry for a little longer."),
        (12, "When the wind picks up, a wider base keeps the boat from tipping over."),
    ],
];

#[derive(Default)]
struct CollectingSink(Mutex<Vec<(LogLevel, String)>>);

impl LogSink for CollectingSink {
    fn log(&self, level: LogLevel, _target: String, message: String, _fields_json: String) {
        self.0.lock().unwrap().push((level, message));
    }
}

fn sibling(env_key: &str, relative: &str) -> PathBuf {
    std::env::var_os(env_key).map(PathBuf::from).unwrap_or_else(|| PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../../lectrice").join(relative))
}

fn config(work: &Path) -> CoreConfig {
    CoreConfig {
        data_dir: work.join("data").display().to_string(),
        cache_dir: work.join("cache").display().to_string(),
        pdfium_library: sibling("LECTRICE_PDFIUM", "backend/vendor/pdfium/lib/libpdfium.so").display().to_string(),
        default_voice: "af_heart".into(),
        read_ahead_minutes: 1,
        keep_minutes_per_document: 2,
        cache_max_mb: 64,
        import_max_mb: 200,
    }
}

/// A two-page PDF with Helvetica text, one line per entry (font size, text).
fn invented_pdf() -> Vec<u8> {
    let mut objects = vec!["<< /Type /Catalog /Pages 2 0 R >>".to_string()];
    let page_ids: Vec<usize> = (0..PAGES.len()).map(|index| 4 + index * 2).collect();
    let kids: Vec<String> = page_ids.iter().map(|id| format!("{id} 0 R")).collect();
    objects.push(format!("<< /Type /Pages /Kids [{}] /Count {} >>", kids.join(" "), PAGES.len()));
    objects.push("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>".to_string());
    for (index, lines) in PAGES.iter().enumerate() {
        let mut content = String::new();
        let mut y = 720;
        for (size, text) in lines.iter() {
            content.push_str(&format!("BT /F1 {size} Tf 72 {y} Td ({text}) Tj ET\n"));
            y -= size * 2;
        }
        objects.push(format!(
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 3 0 R >> >> /Contents {} 0 R >>",
            page_ids[index] + 1
        ));
        objects.push(format!("<< /Length {} >>\nstream\n{content}endstream", content.len()));
    }

    let mut pdf = b"%PDF-1.4\n".to_vec();
    let mut offsets = Vec::new();
    for (index, object) in objects.iter().enumerate() {
        offsets.push(pdf.len());
        pdf.extend_from_slice(format!("{} 0 obj\n{object}\nendobj\n", index + 1).as_bytes());
    }
    let xref_at = pdf.len();
    pdf.extend_from_slice(format!("xref\n0 {}\n0000000000 65535 f \n", objects.len() + 1).as_bytes());
    for offset in offsets {
        pdf.extend_from_slice(format!("{offset:010} 00000 n \n").as_bytes());
    }
    pdf.extend_from_slice(format!("trailer\n<< /Size {} /Root 1 0 R >>\nstartxref\n{xref_at}\n%%EOF\n", objects.len() + 1).as_bytes());
    pdf
}

#[test]
#[ignore = "needs the Kokoro model and pdfium from the sibling lectrice checkout"]
fn imports_an_invented_pdf_and_speaks_its_first_sentence() {
    let work = tempfile::tempdir().unwrap();
    let pdf_path = work.path().join("boats.pdf");
    std::fs::write(&pdf_path, invented_pdf()).unwrap();
    let sink = Arc::new(CollectingSink::default());
    let core = LectriceCore::new(config(work.path()), sink.clone()).unwrap();
    assert_eq!(core.engine_status(), EngineStatus::Missing);

    let imported = core.import_pdf(pdf_path.display().to_string(), "boats.pdf".into()).unwrap();
    assert_eq!(imported.summary.page_count, 2);
    assert!(imported.summary.sentence_count >= 6, "got {} sentences", imported.summary.sentence_count);
    let document = core.get_document(imported.summary.id.clone()).unwrap();
    let order = core.playback_order(imported.summary.id.clone(), false).unwrap();
    assert_eq!(order.len() as u32, imported.summary.sentence_count);
    assert!(!document.blocks.is_empty());

    let model_dir = sibling("LECTRICE_MODEL_DIR", "models/kokoro-multi-lang-v1_0").display().to_string();
    let engine = EngineConfig::Kokoro { model_dir, variant: KokoroVariant::Fp32, num_threads: 4, provider: "cpu".into() };
    let status = core.reload_engine(engine);
    assert!(matches!(status, EngineStatus::Ready { .. }), "{status:?}");
    assert_eq!(core.list_voices().unwrap().voices.len(), 28);

    let args = SpeakArgs { document_id: imported.summary.id, sentence_id: order[1], voice_id: "af_heart".into(), rate: 1.0, read_skipped: false };
    let spoken = core.speak_sentence(args).unwrap();
    assert!(spoken.duration_ms > 1000, "got {} ms", spoken.duration_ms);
    assert_eq!(&spoken.wav[..4], b"RIFF");
    if let Ok(path) = std::env::var("LECTRICE_SMOKE_WAV") {
        std::fs::write(path, &spoken.wav).unwrap();
    }

    let logs = sink.0.lock().unwrap();
    assert!(logs.iter().any(|(_, message)| message == "pdf imported"));
    assert!(!logs.iter().any(|(level, _)| *level == LogLevel::Error));
}

#[cfg(feature = "bench")]
#[test]
#[ignore = "needs the Kokoro model and pdfium from the sibling lectrice checkout"]
fn bench_reports_one_sample_per_text() {
    let work = tempfile::tempdir().unwrap();
    let core = LectriceCore::new(config(work.path()), Arc::new(CollectingSink::default())).unwrap();
    let model_dir = sibling("LECTRICE_MODEL_DIR", "models/kokoro-int8-multi-lang-v1_0").display().to_string();
    let status = core.reload_engine(EngineConfig::Kokoro { model_dir, variant: KokoroVariant::Int8, num_threads: 4, provider: "cpu".into() });
    assert!(matches!(status, EngineStatus::Ready { .. }), "{status:?}");

    let texts = vec!["The kettle clicked off just as the rain began.".to_string(), "Two cups, one spoon, no sugar.".to_string()];
    let result = core.bench_synthesize(texts, "af_heart".into()).unwrap();

    assert_eq!(result.sentences, 2);
    assert_eq!(result.per_sentence.len(), 2);
    assert!(result.audio_ms > 1000 && result.rtf > 0.0);
}
