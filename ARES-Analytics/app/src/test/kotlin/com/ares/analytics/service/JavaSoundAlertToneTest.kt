package com.ares.analytics.service

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.mockito.Mockito.*
import javax.sound.sampled.*
import kotlin.math.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class JavaSoundAlertToneTest {
    private fun assertFailurePreserved(expected: Throwable, actual: Throwable) {
        assertEquals(expected.javaClass, actual.javaClass); assertEquals(expected.message, actual.message)
        // Coroutine stack recovery can copy an exception while retaining the original as its cause.
        assertTrue(generateSequence(actual) { it.cause }.take(8).any { it === expected })
    }
    private class Device {
        val clip = mock(Clip::class.java)
        lateinit var listener: LineListener
        lateinit var bytes: ByteArray
        lateinit var format: AudioFormat
        var onOpen: () -> Unit = {}
        var onStart: () -> Unit = {}
        init {
            doAnswer { listener = it.getArgument(0); null }.`when`(clip).addLineListener(any(LineListener::class.java))
            doAnswer {
                format = it.getArgument(0); bytes = it.getArgument(1)
                assertEquals(0, it.getArgument<Int>(2)); assertEquals(bytes.size, it.getArgument<Int>(3))
                onOpen(); null
            }.`when`(clip).open(any(AudioFormat::class.java), any(ByteArray::class.java), anyInt(), anyInt())
            doAnswer { onStart(); null }.`when`(clip).start()
        }
        fun emit(type: LineEvent.Type) { listener.update(LineEvent(clip, type, 2_400)) }
    }
    @Test fun `completion detaches the listener and closes exactly one clip`() = runTest {
        val device = Device(); device.onStart = { device.emit(LineEvent.Type.STOP) }
        JavaSoundAlertTone(StandardTestDispatcher(testScheduler)) { device.clip }.play()
        verify(device.clip).start(); verify(device.clip).removeLineListener(device.listener); verify(device.clip).close()
        assertEquals(8_000f, device.format.sampleRate); assertEquals(8, device.format.sampleSizeInBits)
        assertEquals(1, device.format.channels); assertEquals(AudioFormat.Encoding.PCM_SIGNED, device.format.encoding)
    }
    @Test fun `duplicate late terminal callbacks cannot resume playback twice`() = runTest {
        val device = Device()
        device.onStart = { device.emit(LineEvent.Type.STOP); device.emit(LineEvent.Type.CLOSE) }
        JavaSoundAlertTone(StandardTestDispatcher(testScheduler)) { device.clip }.play()
        device.emit(LineEvent.Type.STOP); verify(device.clip, times(1)).close()
    }
    @Test fun `start and foreign line events do not terminate active playback`() = runTest {
        val device = Device(); val player = JavaSoundAlertTone(StandardTestDispatcher(testScheduler)) { device.clip }
        val job = launch { player.play() }; runCurrent()
        device.emit(LineEvent.Type.START)
        device.listener.update(LineEvent(mock(Clip::class.java), LineEvent.Type.STOP, 0))
        runCurrent(); assertTrue(job.isActive); verify(device.clip, never()).close()
        device.emit(LineEvent.Type.CLOSE); runCurrent(); assertTrue(job.isCompleted); verify(device.clip).close()
    }
    @Test fun `cancellation closes a suspended clip without a completion event`() = runTest {
        val device = Device(); val player = JavaSoundAlertTone(StandardTestDispatcher(testScheduler)) { device.clip }
        val job = launch { player.play() }; runCurrent(); job.cancelAndJoin()
        verify(device.clip).removeLineListener(device.listener); verify(device.clip).close()
        device.emit(LineEvent.Type.STOP)
    }
    @Test fun `cancellation during open prevents subsequent start and still closes`() = runTest {
        val device = Device(); lateinit var job: Job
        device.onOpen = { job.cancel() }
        val player = JavaSoundAlertTone(StandardTestDispatcher(testScheduler)) { device.clip }
        job = launch { player.play() }; runCurrent()
        assertTrue(job.isCancelled); verify(device.clip, never()).start(); verify(device.clip).close()
    }
    @Test fun `missing terminal events time out and release the clip`() = runTest {
        val device = Device(); val player = JavaSoundAlertTone(StandardTestDispatcher(testScheduler)) { device.clip }
        val job = launch { player.play() }; runCurrent()
        advanceTimeBy(1_999); runCurrent(); assertTrue(job.isActive)
        advanceTimeBy(1); runCurrent(); assertTrue(job.isCancelled); verify(device.clip).close()
    }
    @Test fun `open failure preserves primary error while both cleanups run`() = runTest {
        val device = Device(); val primary = LineUnavailableException("open")
        val removal = IllegalStateException("remove"); val closing = IllegalStateException("close")
        device.onOpen = { throw primary }
        doThrow(removal).`when`(device.clip).removeLineListener(any(LineListener::class.java))
        doThrow(closing).`when`(device.clip).close()
        val player = JavaSoundAlertTone(StandardTestDispatcher(testScheduler)) { device.clip }
        assertFailurePreserved(primary, assertFailsWith<LineUnavailableException> { player.play() })
        assertEquals(listOf<Throwable>(removal, closing), primary.suppressed.toList())
        verify(device.clip).close(); verify(device.clip, never()).start()
    }
    @Test fun `factory failure propagates without manufacturing a line`() = runTest {
        val failure = LineUnavailableException("missing device"); var calls = 0
        val player = JavaSoundAlertTone(StandardTestDispatcher(testScheduler)) { calls++; throw failure }
        assertFailurePreserved(failure, assertFailsWith<LineUnavailableException> { player.play() }); assertEquals(1, calls)
    }
    @Test fun `waveform is cached across independent finite clips`() = runTest {
        val first = Device(); val second = Device()
        first.onStart = { first.emit(LineEvent.Type.STOP) }; second.onStart = { second.emit(LineEvent.Type.STOP) }
        val devices = ArrayDeque(listOf(first, second))
        val player = JavaSoundAlertTone(StandardTestDispatcher(testScheduler)) { devices.removeFirst().clip }
        player.play(); player.play(); assertSame(first.bytes, second.bytes)
        assertEquals(2_400, first.bytes.size); verify(first.clip).close(); verify(second.clip).close()
    }
    @Test fun `waveform duration silence amplitude and spectral tones match the specification`() {
        val pcm = createAlertTonePcm()
        assertEquals(2_400, pcm.size)
        assertTrue(pcm.sliceArray(800 until 1_200).all { it == 0.toByte() })
        assertEquals(127, pcm.maxOf { abs(it.toInt()) })
        fun power(start: Int, count: Int, frequency: Double): Double {
            var real = 0.0; var imaginary = 0.0
            repeat(count) { i ->
                val phase = 2.0 * PI * frequency * i / 8_000.0
                real += pcm[start + i].toInt() * cos(phase); imaginary -= pcm[start + i].toInt() * sin(phase)
            }
            return real * real + imaginary * imaginary
        }
        assertTrue(power(0, 800, 1_000.0) > 1_000.0 * max(1.0, power(0, 800, 1_200.0)))
        assertTrue(power(1_200, 1_200, 1_200.0) > 1_000.0 * max(1.0, power(1_200, 1_200, 1_000.0)))
        assertTrue(abs(pcm.sumOf { it.toInt() }) < pcm.size)
    }
}
