/*
 * Cài đặt quay video khung game: độ phân giải (tỉ lệ phóng), số khung hình,
 * chất lượng (bitrate) và kiểu phóng (pixel vuông / làm mịn).
 * Lưu trong SharedPreferences mặc định, áp dụng cho lần quay kế tiếp.
 */

package javax.microedition.shell;

import android.content.Context;
import android.content.SharedPreferences;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.preference.PreferenceManager;

import ru.playsoftware.j2meloader.R;

public class RecordSettings {
	public static final int[] FPS_VALUES = {15, 20, 30, 60};
	/** 0 = tự động (~480 ở cạnh ngắn), còn lại là hệ số phóng nguyên lần. */
	public static final int[] SCALE_VALUES = {0, 1, 2, 3, 4};
	/** Số bit cho mỗi pixel mỗi khung hình: thấp / trung bình / cao / rất cao. */
	public static final float[] QUALITY_FACTORS = {0.1f, 0.2f, 0.4f, 0.8f};

	private static final String KEY_FPS = "pref_record_fps";
	private static final String KEY_SCALE = "pref_record_scale";
	private static final String KEY_QUALITY = "pref_record_quality";
	private static final String KEY_SMOOTH = "pref_record_smooth";

	public int fps = 60;
	public int scale = 0;
	public int quality = 1;
	public boolean smooth = false;

	public float qualityFactor() {
		return QUALITY_FACTORS[Math.max(0, Math.min(quality, QUALITY_FACTORS.length - 1))];
	}

	public static RecordSettings load(Context context) {
		SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
		RecordSettings s = new RecordSettings();
		int fps = sp.getInt(KEY_FPS, s.fps);
		s.fps = indexOf(FPS_VALUES, fps) >= 0 ? fps : s.fps;
		int scale = sp.getInt(KEY_SCALE, s.scale);
		s.scale = indexOf(SCALE_VALUES, scale) >= 0 ? scale : s.scale;
		int q = sp.getInt(KEY_QUALITY, s.quality);
		s.quality = q >= 0 && q < QUALITY_FACTORS.length ? q : s.quality;
		s.smooth = sp.getBoolean(KEY_SMOOTH, s.smooth);
		return s;
	}

	public void save(Context context) {
		PreferenceManager.getDefaultSharedPreferences(context).edit()
				.putInt(KEY_FPS, fps)
				.putInt(KEY_SCALE, scale)
				.putInt(KEY_QUALITY, quality)
				.putBoolean(KEY_SMOOTH, smooth)
				.apply();
	}

	private static int indexOf(int[] values, int v) {
		for (int i = 0; i < values.length; i++) {
			if (values[i] == v) {
				return i;
			}
		}
		return -1;
	}

	public static void showDialog(Context context) {
		final RecordSettings s = load(context);
		AlertDialog.Builder builder = new AlertDialog.Builder(context);
		Context dc = builder.getContext();
		float density = dc.getResources().getDisplayMetrics().density;
		int pad = (int) (20 * density);

		LinearLayout root = new LinearLayout(dc);
		root.setOrientation(LinearLayout.VERTICAL);
		root.setPadding(pad, (int) (8 * density), pad, 0);

		String[] resLabels = {
				dc.getString(R.string.record_res_auto),
				dc.getString(R.string.record_res_native),
				dc.getString(R.string.record_res_sd),
				dc.getString(R.string.record_res_hd),
				dc.getString(R.string.record_res_x4)};
		final Spinner res = addSpinner(root, R.string.record_resolution, resLabels,
				Math.max(0, indexOf(SCALE_VALUES, s.scale)));
		TextView note = new TextView(dc);
		note.setText(R.string.record_res_note);
		note.setTextSize(12);
		note.setAlpha(0.7f);
		root.addView(note);

		String[] fpsLabels = new String[FPS_VALUES.length];
		for (int i = 0; i < FPS_VALUES.length; i++) {
			fpsLabels[i] = String.valueOf(FPS_VALUES[i]);
		}
		final Spinner fps = addSpinner(root, R.string.record_fps, fpsLabels,
				Math.max(0, indexOf(FPS_VALUES, s.fps)));

		String[] qLabels = {
				dc.getString(R.string.record_q_low),
				dc.getString(R.string.record_q_medium),
				dc.getString(R.string.record_q_high),
				dc.getString(R.string.record_q_ultra)};
		final Spinner quality = addSpinner(root, R.string.record_quality, qLabels, s.quality);

		final CheckBox smooth = new CheckBox(dc);
		smooth.setText(R.string.record_smooth);
		smooth.setChecked(s.smooth);
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
		lp.topMargin = (int) (12 * density);
		root.addView(smooth, lp);

		ScrollView scroll = new ScrollView(dc);
		scroll.addView(root);

		builder.setTitle(R.string.record_settings)
				.setView(scroll)
				.setPositiveButton(android.R.string.ok, (d, w) -> {
					s.scale = SCALE_VALUES[res.getSelectedItemPosition()];
					s.fps = FPS_VALUES[fps.getSelectedItemPosition()];
					s.quality = quality.getSelectedItemPosition();
					s.smooth = smooth.isChecked();
					s.save(context);
				})
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	private static Spinner addSpinner(LinearLayout root, int labelRes, String[] items, int selected) {
		Context c = root.getContext();
		float density = c.getResources().getDisplayMetrics().density;
		TextView label = new TextView(c);
		label.setText(labelRes);
		label.setPadding(0, (int) (12 * density), 0, 0);
		root.addView(label);
		Spinner spinner = new Spinner(c);
		ArrayAdapter<String> adapter = new ArrayAdapter<>(c, android.R.layout.simple_spinner_item, items);
		adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
		spinner.setAdapter(adapter);
		spinner.setSelection(selected);
		root.addView(spinner);
		return spinner;
	}
}
