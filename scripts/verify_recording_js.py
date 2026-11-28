#!/usr/bin/env python3
"""Exercise the shared recording scripts without importing the desktop application.

Node supplies small WebAudio doubles for state/error and waveform assembly tests.
Actual codecs and non-silent MediaRecorder output are covered by AndroidRecordingTest.
"""
from __future__ import annotations

import ast
import json
from pathlib import Path
import shutil
import subprocess
import sys


HARNESS = r"""
const assert = require('assert/strict'), vm = require('vm');
const scripts = JSON.parse(require('fs').readFileSync(0, 'utf8'));
function fixture(options = {}) {
    let starts = 0, resumes = 0, lastBuffer = null, failDecode = false;
    const decoded = {sampleRate:16000, length:16000, numberOfChannels:1, duration:1,
        getChannelData:()=>new Float32Array(16000).fill(.5)};
    class Context {
        constructor() { this.state = options.state || 'suspended'; }
        resume() {
            resumes++;
            if (options.resume === 'reject' || (options.resume === 'rejectAfterFirst' && resumes > 1)) {
                return Promise.reject(new Error('fixture resume blocked'));
            }
            if (options.resume === 'hang') return new Promise(()=>{});
            if (options.resume !== 'nochange') this.state = 'running';
            return Promise.resolve();
        }
        decodeAudioData() {
            if (options.suspendDuringDecode) this.state = 'suspended';
            return failDecode ? Promise.reject(new Error('fixture bad audio')) : Promise.resolve(decoded);
        }
        createMediaStreamDestination() { return {stream:{fixtureStream:true}}; }
        createBuffer(channels, length, sr) {
            const arrays = Array.from({length:channels}, ()=>new Float32Array(length));
            lastBuffer = {numberOfChannels:channels, length, sampleRate:sr,
                duration:length/sr, getChannelData:i=>arrays[i]};
            return lastBuffer;
        }
        createBufferSource() { return {connect(){}, start(){starts++;}}; }
    }
    const sandbox = {navigator:{mediaDevices:{}}, console, Promise, Uint8Array, Float32Array, Number, Error,
        atob:value=>Buffer.from(value, 'base64').toString('binary'),
        // Exercise the real timeout branch without making the test take five seconds.
        setTimeout:(fn, ms)=>setTimeout(fn, ms === 5000 ? 10 : ms), clearTimeout};
    if (!options.unsupported) sandbox[options.prefixed ? 'webkitAudioContext' : 'AudioContext'] = Context;
    sandbox.window = sandbox;
    vm.createContext(sandbox);
    vm.runInContext(scripts.RECORD_MIC_BOOTSTRAP_JS, sandbox);
    const fn = vm.runInContext('(function(){' + scripts.RECORD_MIC_JS + '})', sandbox);
    return {sandbox,
        inject:(payload='', pad=.1, maxDuration=0)=>new Promise(resolve=>fn.call(sandbox, payload, pad, maxDuration, resolve)),
        stats:()=>({starts, resumes, lastBuffer}), failDecode:()=>{failDecode = true;}};
}
(async()=>{
    let tests = 0;
    async function test(name, run) { await run(); tests++; process.stdout.write('ok ' + name + '\n'); }
    await test('awaits running state, clips, pads and preserves nonzero waveform', async()=>{
        const t = fixture();
        const result = await t.inject('dmFsaWQ=', .1, .5);
        assert.equal(result.ok, true); assert.equal(result.state, 'running'); assert.equal(result.dur, .6);
        const pending = t.sandbox.navigator.mediaDevices.getUserMedia({audio:true});
        assert.equal(typeof pending.then, 'function'); assert.equal((await pending).fixtureStream, true);
        const stats = t.stats(); assert.equal(stats.resumes, 1); assert.equal(stats.starts, 1);
        assert.equal(stats.lastBuffer.length, 9600);
        const wave = stats.lastBuffer.getChannelData(0);
        assert.equal(wave[1599], 0); assert.equal(wave[1600], .5); assert.equal(wave[9599], 0);
        assert.equal(t.sandbox.__ucRec.calls, 1);
    });
    await test('empty probe resumes before reporting ready', async()=>{
        const t = fixture(), result = await t.inject();
        assert.equal(result.ok, true); assert.equal(result.state, 'running'); assert.equal(t.stats().resumes, 1);
    });
    await test('resume rejection reaches injector and getUserMedia promise', async()=>{
        const t = fixture({resume:'reject'}), result = await t.inject();
        assert.equal(result.ok, false); assert.match(result.err, /fixture resume blocked/);
        await assert.rejects(t.sandbox.navigator.mediaDevices.getUserMedia({audio:true}), /fixture resume blocked/);
    });
    await test('stuck resume is bounded', async()=>{
        const result = await fixture({resume:'hang'}).inject();
        assert.equal(result.ok, false); assert.match(result.err, /did not resume within 5000/);
    });
    await test('resolved but suspended resume is rejected', async()=>{
        const result = await fixture({resume:'nochange'}).inject();
        assert.equal(result.ok, false); assert.match(result.err, /not running: suspended/);
    });
    await test('closed context is rejected without resuming', async()=>{
        const t = fixture({state:'closed'}), result = await t.inject();
        assert.equal(result.ok, false); assert.match(result.err, /AudioContext is closed/);
        assert.equal(t.stats().resumes, 0);
    });
    await test('bad decoded data cannot reuse previous buffer', async()=>{
        const t = fixture(); assert.equal((await t.inject('dmFsaWQ=')).ok, true); t.failDecode();
        const result = await t.inject('YmFk'); assert.equal(result.ok, false);
        assert.equal(t.sandbox.__ucRec.buf, null); assert.equal(t.sandbox.__ucRec.dur, 0);
        assert.equal(t.sandbox.__ucRec.srcDur, 0);
    });
    await test('context suspended during decoding cannot report success', async()=>{
        const t = fixture({suspendDuringDecode:true, resume:'rejectAfterFirst'});
        const result = await t.inject('dmFsaWQ=');
        assert.equal(result.ok, false); assert.match(result.err, /fixture resume blocked/);
        assert.equal(t.sandbox.__ucRec.buf, null); assert.equal(t.stats().resumes, 2);
    });
    await test('missing bootstrap and unsupported audio report explicit errors', async()=>{
        const t = fixture({unsupported:true});
        assert.match((await t.inject()).err, /Web Audio API is unavailable/);
        vm.runInContext('window.__ucRec=undefined;', t.sandbox);
        assert.equal((await t.inject()).err, 'bootstrap missing');
    });
    await test('WebKit constructor fallback remains supported', async()=>{
        assert.equal((await fixture({prefixed:true}).inject()).ok, true);
    });
    await test('invalid pad and duration are rejected', async()=>{
        for (const pair of [[-1,0], [NaN,0], [0,Infinity], [0,-1]]) {
            const result = await fixture().inject('dmFsaWQ=', ...pair);
            assert.equal(result.ok, false); assert.match(result.err, /finite non-negative/);
        }
    });
    await test('bootstrap is idempotent and keeps the decoded buffer', async()=>{
        const t = fixture(); assert.equal((await t.inject('dmFsaWQ=')).ok, true);
        const previous = t.sandbox.__ucRec, buffer = previous.buf;
        vm.runInContext(scripts.RECORD_MIC_BOOTSTRAP_JS, t.sandbox);
        assert.equal(t.sandbox.__ucRec, previous); assert.equal(t.sandbox.__ucRec.buf, buffer);
    });
    process.stdout.write('PASS ' + tests + ' recording script cases\n');
})().catch(error=>{console.error(error); process.exitCode = 1;});
"""


def main() -> int:
    node = shutil.which("node")
    if not node:
        print("Node.js is required to verify the shared recording scripts", file=sys.stderr)
        return 1
    source = Path(__file__).resolve().parents[1] / "UnipusAI_Helper.py"
    wanted = {"RECORD_MIC_BOOTSTRAP_JS", "RECORD_MIC_JS"}
    constants = {}
    for item in ast.parse(source.read_text(encoding="utf-8-sig")).body:
        if isinstance(item, ast.Assign):
            for target in item.targets:
                if isinstance(target, ast.Name) and target.id in wanted:
                    constants[target.id] = ast.literal_eval(item.value)
    if set(constants) != wanted:
        raise RuntimeError("Shared recording constants were not found")
    result = subprocess.run([node, "-e", HARNESS], input=json.dumps(constants),
                            text=True, encoding="utf-8", capture_output=True, timeout=30)
    print(result.stdout, end="")
    print(result.stderr, end="", file=sys.stderr)
    return result.returncode


if __name__ == "__main__":
    raise SystemExit(main())
