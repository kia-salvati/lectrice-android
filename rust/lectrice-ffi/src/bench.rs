//! Spike only (feature `bench`): synthesis timings straight from the engine, without the cache.

use std::time::Instant;

use lectrice_app::UseCase;
use lectrice_domain::{AppError, AudioEncoder, ErrorName, SpeechRequest, VoiceId};
use lectrice_infra::WavEncoder;
use tracing::info;

use crate::api::LectriceCore;
use crate::composition::elapsed_ms;
use crate::errors::CoreResult;

const TARGET: &str = "lectrice_ffi::bench";
const MS_PER_SECOND: u64 = 1000;

#[derive(Debug, Clone, uniffi::Record)]
pub struct BenchResult {
    pub engine_load_ms: u64,
    pub sentences: u32,
    pub audio_ms: u64,
    pub synth_ms: u64,
    /// Synthesis time ÷ audio time.
    pub rtf: f64,
    pub first_sentence_ms: u64,
    pub per_sentence: Vec<BenchSample>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct BenchSample {
    pub index: u32,
    pub chars: u32,
    pub audio_ms: u32,
    pub synth_ms: u32,
    /// When this sentence finished, from the start of the run.
    pub at_ms: u64,
}

#[uniffi::export]
impl LectriceCore {
    /// Stops the read-ahead first, so it doesn't share the CPU with the run.
    pub fn bench_synthesize(&self, texts: Vec<String>, voice_id: String) -> CoreResult<BenchResult> {
        let loaded = self.read();
        let Some(engine) = &loaded.engine else {
            return Err(AppError::new(ErrorName::SpeechEngineUnavailable).with_detail("no engine loaded").into());
        };
        loaded.services.speech()?.stop_all_read_ahead.run(&self.context(), ())?;

        let voice_id = VoiceId::new(voice_id);
        let started = Instant::now();
        let mut per_sentence = Vec::with_capacity(texts.len());
        for (index, text) in texts.into_iter().enumerate() {
            let chars = text.chars().count();
            let synth_started = Instant::now();
            let audio = engine.engine.synthesize(SpeechRequest { text, voice_id: voice_id.clone() })?;
            let synth_ms = elapsed_ms(synth_started);
            let audio_ms = (audio.samples.len() as u64 * MS_PER_SECOND).checked_div(u64::from(audio.sample_rate)).unwrap_or_default();
            per_sentence.push(BenchSample {
                index: to_u32(index as u64),
                chars: to_u32(chars as u64),
                audio_ms: to_u32(audio_ms),
                synth_ms: to_u32(synth_ms),
                at_ms: elapsed_ms(started),
            });
        }

        let audio_ms: u64 = per_sentence.iter().map(|sample| u64::from(sample.audio_ms)).sum();
        let synth_ms: u64 = per_sentence.iter().map(|sample| u64::from(sample.synth_ms)).sum();
        let rtf = if audio_ms == 0 { 0.0 } else { synth_ms as f64 / audio_ms as f64 };
        let result = BenchResult {
            engine_load_ms: engine.load_ms,
            sentences: to_u32(per_sentence.len() as u64),
            audio_ms,
            synth_ms,
            rtf,
            first_sentence_ms: per_sentence.first().map(|sample| u64::from(sample.synth_ms)).unwrap_or_default(),
            per_sentence,
        };
        info!(target: TARGET, engine_id = engine.engine.id(), sentences = result.sentences, audio_ms, synth_ms, rtf, "bench run finished");
        Ok(result)
    }

    /// One clip as WAV, so the user can listen to what each provider made.
    pub fn bench_speak(&self, text: String, voice_id: String) -> CoreResult<Vec<u8>> {
        let loaded = self.read();
        let Some(engine) = &loaded.engine else {
            return Err(AppError::new(ErrorName::SpeechEngineUnavailable).with_detail("no engine loaded").into());
        };
        let audio = engine.engine.synthesize(SpeechRequest { text, voice_id: VoiceId::new(voice_id) })?;
        Ok(WavEncoder.encode(&audio)?)
    }
}

fn to_u32(value: u64) -> u32 {
    u32::try_from(value).unwrap_or(u32::MAX)
}
