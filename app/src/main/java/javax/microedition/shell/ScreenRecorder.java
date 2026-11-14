/*
 * Quay riêng khung game (không dính bàn phím ảo / thanh phím mềm).
 * Lấy hình trực tiếp từ bộ đệm offscreen của Canvas, phóng to nguyên lần (nearest),
 * mã hóa H.264 bằng MediaCodec (input Surface) và ghi ra MP4 bằng MediaMuxer.
 * Không cần quyền ghi màn hình, không cần Foreground Service. Chưa có âm thanh.
 */

package javax.microedition.shell;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaScannerConnection;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;

import androidx.annotation.RequiresApi;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

import javax.microedition.lcdui.Canvas;
import javax.microedition.util.ContextHolder;

@RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
public class ScreenRecorder implements Runnable {

	/** Trả về Canvas đang hiển thị (hoặc null nếu hiện tại không phải Canvas). */
	public interface FrameSource {
		Canvas getCanvas();
	}

	/** Được gọi trên main thread khi quay xong (path != null) hoặc lỗi (error != null). */
	public interface Listener {
		void onFinished(ScreenRecorder recorder, String path, Throwable error);
	}

	private static final String MIME = "video/avc";
	// Chế độ tự động: phóng to theo số nguyên để cạnh ngắn ~480 (240x320 -> 480x640)
	private static final int TARGET_SHORT_SIDE = 480;
	private static final int MAX_LONG_SIDE = 1920;

	private final FrameSource source;
	private final File file;
	private final Listener listener;
	private final RecordSettings settings;
	private final Handler mainHandler = new Handler(Looper.getMainLooper());
	private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

	private volatile boolean running;
	private MediaCodec codec;
	private MediaMuxer muxer;
	private int trackIndex = -1;
	private boolean muxerStarted;
	private long firstPts = -1;

	public ScreenRecorder(FrameSource source, File file, RecordSettings settings, Listener listener) {
		this.source = source;
		this.file = file;
		this.settings = settings;
		this.listener = listener;
	}

	public void start() {
		running = true;
		new Thread(this, "ScreenRecorder").start();
	}

	/** Yêu cầu dừng; file được hoàn thiện ở thread nền rồi báo qua Listener. */
	public void stop() {
		running = false;
	}

	public boolean isRunning() {
		return running;
	}

	private Bitmap grab(Bitmap reuse) {
		Canvas canvas = source.getCanvas();
		return canvas == null ? null : canvas.grabFrame(reuse);
	}

	private static int align16(int v) {
		return (v + 15) & ~15;
	}

	@Override
	public void run() {
		Surface surface = null;
		Bitmap frame = null;
		Throwable error = null;
		try {
			// Chờ khung hình đầu tiên (tối đa 3 giây)
			long deadline = System.currentTimeMillis() + 3000;
			while (running && frame == null && System.currentTimeMillis() < deadline) {
				frame = grab(null);
				if (frame == null) {
					Thread.sleep(50);
				}
			}
			if (frame == null) {
				throw new IOException("No frame available");
			}

			int srcW = frame.getWidth();
			int srcH = frame.getHeight();
			// Phóng to theo số nguyên rồi làm tròn lên bội số của 16 cho bộ mã hóa
			final int fps = settings.fps;
			int scale = settings.scale > 0 ? settings.scale
					: Math.max(1, TARGET_SHORT_SIDE / Math.min(srcW, srcH));
			while (scale > 1 && Math.max(srcW, srcH) * scale > MAX_LONG_SIDE) {
				scale--;
			}
			int outW = align16(srcW * scale);
			int outH = align16(srcH * scale);
			// Bitrate theo chất lượng đã chọn (số bit trên mỗi pixel mỗi khung hình)
			long rate = (long) (outW * (long) outH * fps * settings.qualityFactor());
			int bitrate = (int) Math.max(250000L, Math.min(20000000L, rate));

			MediaFormat format = MediaFormat.createVideoFormat(MIME, outW, outH);
			format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
					MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
			format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
			format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
			format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

			codec = MediaCodec.createEncoderByType(MIME);
			codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
			surface = codec.createInputSurface();
			codec.start();

			File dir = file.getParentFile();
			if (dir != null && !dir.exists() && !dir.mkdirs()) {
				throw new IOException("Can't create directory: " + dir);
			}
			muxer = new MediaMuxer(file.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

			Paint paint = new Paint();
			paint.setFilterBitmap(settings.smooth); // mặc định: pixel vuông, sắc nét
			Rect src = new Rect();
			Rect dst = new Rect();
			long interval = 1000000000L / fps;
			long next = System.nanoTime();
			while (running) {
				Bitmap b = grab(frame);
				if (b != null) {
					frame = b;
				}
				drawFrame(surface, frame, outW, outH, paint, src, dst);
				drain(false);
				next += interval;
				long wait = next - System.nanoTime();
				if (wait > 0) {
					Thread.sleep(wait / 1000000L, (int) (wait % 1000000L));
				} else {
					next = System.nanoTime(); // máy chậm: bỏ qua, không dồn khung hình
				}
			}
			codec.signalEndOfInputStream();
			drain(true);
		} catch (Throwable e) {
			error = e;
		} finally {
			running = false;
			if (surface != null) {
				surface.release();
			}
			if (codec != null) {
				try {
					codec.stop();
				} catch (Exception ignored) {
				}
				codec.release();
			}
			if (muxer != null) {
				try {
					if (muxerStarted) {
						muxer.stop();
					}
				} catch (Exception e) {
					if (error == null) {
						error = e;
					}
				}
				try {
					muxer.release();
				} catch (Exception ignored) {
				}
			}
			if (frame != null) {
				frame.recycle();
			}
		}

		final Throwable err = error;
		final String path = file.getAbsolutePath();
		if (err == null && muxerStarted) {
			MediaScannerConnection.scanFile(ContextHolder.getAppContext(),
					new String[]{path}, new String[]{"video/mp4"}, null);
		} else {
			//noinspection ResultOfMethodCallIgnored
			file.delete();
		}
		mainHandler.post(() -> listener.onFinished(this,
				err == null && muxerStarted ? path : null,
				err != null ? err : (muxerStarted ? null : new IOException("Empty video"))));
	}

	private void drawFrame(Surface surface, Bitmap frame, int outW, int outH,
						   Paint paint, Rect src, Rect dst) {
		android.graphics.Canvas c = surface.lockCanvas(null);
		try {
			c.drawColor(Color.BLACK);
			int w = frame.getWidth();
			int h = frame.getHeight();
			float s = Math.min((float) outW / w, (float) outH / h);
			if (s >= 1f && !settings.smooth) {
				s = (float) Math.floor(s); // phóng to nguyên lần, giữ pixel vuông vức
			}
			int dw = Math.round(w * s);
			int dh = Math.round(h * s);
			int l = (outW - dw) / 2;
			int t = (outH - dh) / 2;
			src.set(0, 0, w, h);
			dst.set(l, t, l + dw, t + dh);
			c.drawBitmap(frame, src, dst, paint);
		} finally {
			surface.unlockCanvasAndPost(c);
		}
	}

	@SuppressWarnings("deprecation")
	private void drain(boolean end) {
		int idle = 0;
		while (true) {
			int idx = codec.dequeueOutputBuffer(info, end ? 10000 : 0);
			if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
				if (!end || ++idle > 300) {
					return;
				}
			} else if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
				trackIndex = muxer.addTrack(codec.getOutputFormat());
				muxer.start();
				muxerStarted = true;
			} else if (idx >= 0) {
				ByteBuffer data = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
						? codec.getOutputBuffer(idx) : codec.getOutputBuffers()[idx];
				if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
					info.size = 0;
				}
				if (info.size > 0 && muxerStarted && data != null) {
					if (firstPts < 0) {
						firstPts = info.presentationTimeUs;
					}
					info.presentationTimeUs -= firstPts;
					data.position(info.offset);
					data.limit(info.offset + info.size);
					muxer.writeSampleData(trackIndex, data, info);
				}
				codec.releaseOutputBuffer(idx, false);
				if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
					return;
				}
			}
		}
	}
}
