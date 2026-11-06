package com.example.ostpick;

import android.content.Context;
import android.database.sqlite.SQLiteException;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.widget.HorizontalScrollView;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.rscja.barcode.BarcodeFactory;
import com.rscja.barcode.BarcodeDecoder;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.net.UnknownHostException;
import java.net.SocketTimeoutException;
import javax.net.ssl.SSLException;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.text.SimpleDateFormat;

/**
 * 配套文件：上一版 activity_main.xml、OrderDbHelper.java、app/libs/DeviceAPI.aar。
 * 采集页扫描只填单号，按钮确认后新增或更新库位；保留现有 DONE 状态。
 * 实体键 139 直接扫描；支持 Google Sheet B 列单号与 D 列库位导入；本地 Excel 导入尚未实现。
 */
public class MainActivity extends AppCompatActivity {
    private static final String SCAN_TAG = "OSTPICK_SCAN";
    private static final String DEFAULT_SHEET_URL =
            "https://docs.google.com/spreadsheets/d/1Oxlv5erb7EeCoH9N2sPvyZjm6EQ2mWLYqkE55IcZlCQ/edit?gid=0#gid=0";
    private final ExecutorService sheetExecutor = Executors.newSingleThreadExecutor();
    private EditText sheetAddress;
    private CheckBox skipSheetHeader;
    private Button importSheetButton;
    private TextView sheetStatus;
    private boolean googleImportRunning = false;
    private View[] pages;
    private View[] navigation;
    private volatile int currentPage = 0;
    private OrderDbHelper database;
    private TableLayout tableOrders;
    private TextView textOrderCount;
    private EditText inputCollect;
    private EditText inputLocation;
    private TextView textCollectResult;
    private BarcodeDecoder barcodeDecoder;
    private boolean scannerReady = false;
    private boolean scanInProgress = false;
    private volatile boolean activityResumed = false;
    private volatile long scanGeneration = 0;
    private volatile long scannerSession = 0;
    private EditText inputMatch;
    private TextView textMatchResult;
    private TextView textPending;
    private long pendingId = -1;
    private String pendingNumber = null;
    private String pendingLocation = "";
    private ToneGenerator tone;
    private AlertDialog candidateDialog;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets keyboard = insets.getInsets(WindowInsetsCompat.Type.ime());
            view.setPadding(bars.left, bars.top, bars.right,
                    Math.max(bars.bottom, keyboard.bottom));
            return insets;
        });

        database = new OrderDbHelper(this);
        tableOrders = findViewById(R.id.tableOrders);
        textOrderCount = findViewById(R.id.textOrderCount);
        inputCollect = findViewById(R.id.inputCollect);
        textCollectResult = findViewById(R.id.textCollectResult);
        inputMatch = findViewById(R.id.inputMatch);
        textMatchResult = findViewById(R.id.textMatchResult);
        textPending = findViewById(R.id.textPending);
        try {
            tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 90);
        } catch (RuntimeException error) {
            Log.w(SCAN_TAG, "提示音初始化失败", error);
        }
        pages = new View[]{findViewById(R.id.pageMatch),
                findViewById(R.id.pageOrders), findViewById(R.id.pageCollect)};
        navigation = new View[]{findViewById(R.id.navMatch),
                findViewById(R.id.navOrders), findViewById(R.id.navCollect)};
        for (int i = 0; i < navigation.length; i++) {
            final int index = i;
            navigation[i].setOnClickListener(view -> showPage(index));
        }
        findViewById(R.id.btnCollect).setOnClickListener(view -> collectOrder());
        inputCollect.setImeOptions(EditorInfo.IME_ACTION_DONE);
        // 回车和软键盘“完成”只移动焦点，不保存。
        // 只有点击“加入单号列表”才写入采集数据。
        inputCollect.setOnEditorActionListener((view, actionId, event) -> {
            if (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER) {
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                    prepareCollectedInput();
                }
                return true;
            }
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_NEXT) {
                prepareCollectedInput();
                return true;
            }
            return false;
        });
        findViewById(R.id.btnAdd).setOnClickListener(view -> showOrderDialog(null));
        findViewById(R.id.btnDeleteAll).setOnClickListener(view -> confirmDeleteAll());
        findViewById(R.id.btnMatch).setOnClickListener(view -> submitMatchInput());
        inputMatch.setImeOptions(EditorInfo.IME_ACTION_DONE);
        inputMatch.setOnEditorActionListener((view, actionId, event) -> {
            if (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER) {
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                    submitMatchInput();
                }
                return true;
            }
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submitMatchInput();
                return true;
            }
            return false;
        });
        findViewById(R.id.btnImportExcel).setOnClickListener(
                view -> toast("Excel 导入功能将在后续接入"));

        // 兼容已添加或尚未添加 btnHardwareScan 的两种 XML。
        // 用字符串查找资源，避免旧布局没有该 ID 时出现编译错误。
        int scanButtonId = getResources().getIdentifier(
                "btnHardwareScan", "id", getPackageName());
        View scanButton = scanButtonId == 0 ? null : findViewById(scanButtonId);
        if (scanButton == null) {
            Button button = new Button(this);
            button.setText("启动机载扫描");
            View collectButton = findViewById(R.id.btnCollect);
            LinearLayout parent = (LinearLayout) collectButton.getParent();
            parent.addView(button, parent.indexOfChild(collectButton) + 1,
                    new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT));
            scanButton = button;
        }
        scanButton.setVisibility(View.GONE);
        Button matchScanButton = new Button(this);
        matchScanButton.setText("启动机载扫描（箱单 / DONE）");
        View matchButton = findViewById(R.id.btnMatch);
        LinearLayout matchParent = (LinearLayout) matchButton.getParent();
        matchParent.addView(matchScanButton, matchParent.indexOfChild(matchButton) + 1,
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
        matchScanButton.setVisibility(View.GONE);
        setupLocationInput(savedInstanceState);
        setupCompactTable();
        textMatchResult.setTextSize(20);
        setupGoogleSheetImport();
        showPage(savedInstanceState == null ? 0
                : savedInstanceState.getInt("currentPage", 0));
    }

    private void showPage(int index) {
        stopHardwareScan();
        dismissCandidates();
        clearPending();
        if (textMatchResult != null) showMatchResult("等待扫描", "#E2E8F0");
        if (index < 0 || index >= pages.length) index = 0;
        View focused = getCurrentFocus();
        if (focused != null) {
            InputMethodManager keyboard = (InputMethodManager)
                    getSystemService(Context.INPUT_METHOD_SERVICE);
            if (keyboard != null) keyboard.hideSoftInputFromWindow(focused.getWindowToken(), 0);
            focused.clearFocus();
        }
        currentPage = index;
        for (int i = 0; i < pages.length; i++) {
            pages[i].setVisibility(i == index ? View.VISIBLE : View.GONE);
            navigation[i].setEnabled(i != index);
            navigation[i].setAlpha(i == index ? 0.55f : 1.0f);
        }
        if (index == 1) {
            refreshOrders();
            View scroll = (View) tableOrders.getParent();
            if (scroll instanceof HorizontalScrollView) ((HorizontalScrollView) scroll).scrollTo(0, 0);
        }
        if (index == 2) inputCollect.requestFocus();
        if (index == 0) inputMatch.requestFocus();
    }

    private String validateNumber(String number, long excludedId) {
        if (number.isEmpty()) return "请输入单号";
        if ("DONE".equalsIgnoreCase(number)) return "DONE 是完成指令，不能作为基础单号";
        if (number.contains("\n") || number.contains("\r") || number.indexOf('\0') >= 0) return "一次只能录入一个单号";
        if (database.exists(number, excludedId)) return "该单号已存在";
        return null;
    }

    private String validateCollectedNumber(String number) {
        if (number.isEmpty()) return "请输入或扫描单号";
        if ("DONE".equalsIgnoreCase(number)) return "DONE 是完成指令，不能作为基础单号";
        if (number.contains("\n") || number.contains("\r") || number.indexOf('\0') >= 0) {
            return "一次只能录入一个单号";
        }
        // 采集页允许同号再次保存；列表编辑仍使用原来的重复校验。
        return null;
    }

    private void prepareCollectedInput() {
        String number = inputCollect.getText().toString().trim();
        String error = validateCollectedNumber(number);
        if (error != null) {
            showCollectResult(error, false);
            return;
        }
        textCollectResult.setText("待保存：" + number
                + "\n请确认库位，再点击“加入单号列表”；同号将更新库位。");
        textCollectResult.setTextColor(Color.parseColor("#92400E"));
        inputLocation.requestFocus();
        inputLocation.setSelection(inputLocation.length());
    }

    private void collectOrder() {
        if (googleImportRunning) {
            toast("正在导入表格，请稍候");
            return;
        }
        String number = inputCollect.getText().toString().trim();
        try {
            String error = validateCollectedNumber(number);
            if (error != null) {
                showCollectResult(error, false);
                inputCollect.selectAll();
                return;
            }
            String location = inputLocation.getText().toString().trim();
            String locationError = validateLocation(location);
            if (locationError != null) {
                inputLocation.setError(locationError);
                showCollectResult(locationError, false);
                return;
            }
            boolean created = database.saveCollected(number, location);
            inputCollect.setText("");
            showCollectResult((created ? "已新增：" : "已更新：") + number
                    + "\n库位：" + displayLocation(location), true);
        } catch (SQLiteException error) {
            Log.e(SCAN_TAG, "保存单号失败", error);
            showCollectResult("保存失败，单号未录入，请重试", false);
        }
        inputCollect.requestFocus();
    }

    private void showCollectResult(String message, boolean success) {
        textCollectResult.setText(message);
        textCollectResult.setTextColor(Color.parseColor(success ? "#166534" : "#B91C1C"));
    }

    private void showOrderDialog(OrderDbHelper.Order order) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint("单号");
        EditText locationInput = new EditText(this);
        locationInput.setSingleLine(true);
        locationInput.setInputType(InputType.TYPE_CLASS_TEXT);
        locationInput.setHint("库位（可选）");
        if (order != null) {
            input.setText(order.number);
            input.setSelection(input.length());
            locationInput.setText(order.location);
        }
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(20), dp(8), dp(20), 0);
        TextView numberLabel = new TextView(this);
        numberLabel.setText("单号");
        container.addView(numberLabel);
        container.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView locationLabel = new TextView(this);
        locationLabel.setText("库位（仅修改库位会保留完成状态）");
        container.addView(locationLabel);
        container.addView(locationInput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(order == null ? "新增单号" : "修改单号 / 库位")
                .setView(container).setNegativeButton("取消", null)
                .setPositiveButton("保存", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    String number = input.getText().toString().trim();
                    String location = locationInput.getText().toString().trim();
                    try {
                        String error = validateNumber(number, order == null ? -1 : order.id);
                        if (error != null) {
                            input.setError(error);
                            return;
                        }
                        String locationError = validateLocation(location);
                        if (locationError != null) {
                            locationInput.setError(locationError);
                            return;
                        }
                        if (order == null) database.add(number, location);
                        else if (!number.equals(order.number) || !location.equals(order.location)) {
                            database.update(order.id, number, location);
                        }
                        dialog.dismiss();
                        refreshOrders();
                    } catch (SQLiteException error) {
                        input.setError("保存失败，请重试");
                    }
                }));
        dialog.show();
    }

    private void setupCompactTable() {
        View page = findViewById(R.id.pageOrders);
        page.setPadding(dp(8), dp(12), dp(8), dp(8));
        textOrderCount.setTextSize(13);
        page.addOnLayoutChangeListener((view, left, top, right, bottom,
                                       oldLeft, oldTop, oldRight, oldBottom) -> {
            if (currentPage == 1 && right - left != oldRight - oldLeft) refreshOrders();
        });
    }

    private int[] tableColumnWidths() {
        View page = findViewById(R.id.pageOrders);
        int available = page.getWidth() - page.getPaddingLeft() - page.getPaddingRight();
        if (available <= 0) {
            available = getResources().getDisplayMetrics().widthPixels - dp(16);
        }
        // 前四列总宽严格等于可视区域。单号/库位过长时换行，不挤走删除按钮。
        int delete = Math.min(dp(48), available / 5);
        int status = Math.min(dp(48), available / 5);
        int location = available * 24 / 100;
        int number = available - delete - status - location;
        return new int[]{number, status, location, delete, dp(52), dp(142), dp(38)};
    }

    private void refreshOrders() {
        try {
            List<OrderDbHelper.Order> orders = database.getAll();
            int[] widths = tableColumnWidths();
            tableOrders.removeAllViews();
            String[] titles = {"单号", "状态", "库位", "删除", "编辑", "完成时间", "序号"};
            TableRow header = new TableRow(this);
            header.setBackgroundColor(Color.parseColor("#E2E8F0"));
            for (int i = 0; i < titles.length; i++) {
                TextView cell = makeCompactCell(titles[i], widths[i], i == 6 ? 10 : 11);
                cell.setTypeface(null, Typeface.BOLD);
                header.addView(cell);
            }
            tableOrders.addView(header);
            int completed = 0;
            int sequence = 1;
            for (OrderDbHelper.Order order : orders) {
                if (order.done) completed++;
                TableRow row = new TableRow(this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setBackgroundColor(Color.parseColor(order.done ? "#DCFCE7" : "#FFFFFF"));
                row.addView(makeCompactCell(order.number, widths[0], 12));
                row.addView(makeCompactCell(order.done ? "DONE" : "未完成", widths[1], 11));
                row.addView(makeCompactCell(displayLocation(order.location), widths[2], 12));
                Button delete = makeTableButton("删除", widths[3], "#B91C1C");
                delete.setOnClickListener(view -> confirmDelete(order));
                row.addView(delete);
                Button edit = makeTableButton("编辑", widths[4], "#1D4ED8");
                edit.setOnClickListener(view -> showOrderDialog(order));
                row.addView(edit);
                row.addView(makeCompactCell(order.completedAt.isEmpty() ? "—" : order.completedAt,
                        widths[5], 11));
                row.addView(makeCompactCell(String.valueOf(sequence++), widths[6], 10));
                tableOrders.addView(row);
            }
            textOrderCount.setText("共 " + orders.size() + " 条 · 未完成 "
                    + (orders.size() - completed) + " 条 · 已完成 " + completed + " 条");
        } catch (SQLiteException error) {
            Log.e(SCAN_TAG, "读取列表失败", error);
            toast("读取单号列表失败，请重试");
        }
    }

    private TextView makeCompactCell(String text, int widthPx, int textSp) {
        TextView cell = new TextView(this);
        cell.setText(text);
        cell.setTextSize(textSp);
        cell.setTextColor(Color.parseColor("#183153"));
        cell.setPadding(dp(3), dp(8), dp(3), dp(8));
        cell.setSingleLine(false);
        cell.setMaxWidth(widthPx);
        cell.setGravity(Gravity.CENTER_VERTICAL);
        cell.setLayoutParams(new TableRow.LayoutParams(widthPx,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return cell;
    }

    private Button makeTableButton(String label, int widthPx, String textColor) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(11);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setPadding(0, 0, 0, 0);
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setTextColor(Color.parseColor(textColor));
        button.setLayoutParams(new TableRow.LayoutParams(widthPx,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return button;
    }

    private void confirmDelete(OrderDbHelper.Order order) {
        new AlertDialog.Builder(this).setTitle("删除单号")
                .setMessage("确定删除「" + order.number + "」吗？")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) -> {
                    try {
                        database.delete(order.id);
                        refreshOrders();
                    } catch (SQLiteException error) {
                        toast("删除失败，请重试");
                    }
                }).show();
    }

    private void confirmDeleteAll() {
        new AlertDialog.Builder(this).setTitle("删除全部单号")
                .setMessage("将删除所有单号及完成记录，无法撤销。是否继续？")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除所有", (dialog, which) -> {
                    try {
                        database.deleteAll();
                        refreshOrders();
                        textCollectResult.setText("等待采集");
                        toast("已删除全部单号");
                    } catch (SQLiteException error) {
                        toast("删除失败，请重试");
                    }
                }).show();
    }

    private void openHardwareScanner() {
        if (scannerReady || !activityResumed) return;
        try {
            barcodeDecoder = BarcodeFactory.getInstance().getBarcodeDecoder();
            if (barcodeDecoder == null) {
                Log.e(SCAN_TAG, "decoder=null");
                showScannerStatus("未获取到扫描器，请确认设备与 SDK 是否匹配", false);
                return;
            }
            scannerReady = barcodeDecoder.open(this);
            Log.i(SCAN_TAG, "open=" + scannerReady);
            if (!scannerReady) {
                releaseHardwareScanner();
                showScannerStatus("扫描器打开失败，请退出其他扫描程序后重试", false);
                return;
            }

            // 打开后立即注册回调，不依赖点击按钮或第一次按实体键。
            final long session = ++scannerSession;
            barcodeDecoder.setDecodeCallback(entity -> {
                final boolean success = entity != null
                        && entity.getResultCode() == BarcodeDecoder.DECODE_SUCCESS;
                final String barcode = success ? entity.getBarcodeData() : null;
                final int callbackPage = currentPage;
                final long callbackGeneration = scanGeneration;
                Log.d(SCAN_TAG, "decode success=" + success + ", page=" + callbackPage);
                runOnUiThread(() -> {
                    if (session != scannerSession || !activityResumed || !scannerReady
                            || callbackPage != currentPage
                            || callbackGeneration != scanGeneration
                            || (currentPage != 0 && currentPage != 2)) return;

                    if (googleImportRunning) return;
                    boolean appRequestedScan = scanInProgress;
                    scanInProgress = false;
                    // 成功结果不受 scanInProgress 限制：允许设备自身触发扫描。
                    if (!success || barcode == null || barcode.trim().isEmpty()) {
                        // 忽略系统独立触发的取消通知，避免覆盖页面提示。
                        if (appRequestedScan) {
                            if (currentPage == 0) clearPending();
                            showScannerStatus("未读取到有效条码，请重新扫描", false);
                        }
                        return;
                    }
                    if (currentPage == 2) {
                        inputCollect.setText(barcode);
                        prepareCollectedInput();
                    } else {
                        inputMatch.setText(barcode);
                        submitMatchInput();
                    }
                });
            });
            Log.i(SCAN_TAG, "callback registered; ready for physical key 139");
            if (currentPage == 2) {
                showCollectResult("扫描器已就绪，请按机身扫描键或手工输入", true);
            }
        } catch (Exception | LinkageError error) {
            Log.e(SCAN_TAG, "打开扫描器失败", error);
            releaseHardwareScanner();
            showScannerStatus("扫描器初始化失败，请查看 OSTPICK_SCAN 日志", false);
        }
    }

    private void startHardwareScan() {
        if (googleImportRunning) {
            toast("正在导入表格，请稍候");
            return;
        }
        if ((currentPage != 0 && currentPage != 2) || !activityResumed) return;
        if (!scannerReady || barcodeDecoder == null) openHardwareScanner();
        if (!scannerReady || barcodeDecoder == null) return;
        if (scanInProgress) {
            toast("正在扫描，请等待结果");
            return;
        }
        dismissCandidates();
        scanGeneration++;
        try {
            scanInProgress = true;
            showScannerStatus("正在扫描，请对准条码……", true);
            boolean started = barcodeDecoder.startScan();
            Log.i(SCAN_TAG, "startScan=" + started + ", page=" + currentPage);
            if (!started) {
                scanInProgress = false;
                scanGeneration++;
                if (currentPage == 0) clearPending();
                showScannerStatus("启动扫描失败，请重试", false);
            }
        } catch (Exception | LinkageError error) {
            stopHardwareScan();
            Log.e(SCAN_TAG, "启动扫描失败", error);
            if (currentPage == 0) clearPending();
            showScannerStatus("启动扫描异常，请查看 OSTPICK_SCAN 日志", false);
        }
    }

    private void stopHardwareScan() {
        scanInProgress = false;
        scanGeneration++;
        // 设备实体键也可能独立启动扫描，因此不能只检查 App 的启动标志。
        if (barcodeDecoder != null && scannerReady) {
            try {
                barcodeDecoder.stopScan();
            } catch (Exception | LinkageError error) {
                Log.e(SCAN_TAG, "停止扫描失败", error);
            }
        }
    }

    private void releaseHardwareScanner() {
        scannerSession++;
        stopHardwareScan();
        scannerReady = false;
        if (barcodeDecoder != null) {
            try {
                barcodeDecoder.close();
            } catch (Exception | LinkageError error) {
                Log.e(SCAN_TAG, "关闭扫描器失败", error);
            } finally {
                barcodeDecoder = null;
            }
        }
    }

    private void hideKeyboardForHardwareScan() {
        View root = findViewById(R.id.main);
        View focused = getCurrentFocus();
        View tokenView = focused != null ? focused : root;
        InputMethodManager keyboard = (InputMethodManager)
                getSystemService(Context.INPUT_METHOD_SERVICE);
        if (keyboard != null) {
            keyboard.hideSoftInputFromWindow(tokenView.getWindowToken(), 0);
        }
        WindowInsetsControllerCompat controller = ViewCompat.getWindowInsetsController(root);
        if (controller != null) controller.hide(WindowInsetsCompat.Type.ime());
        // 焦点移出输入框，保持文字不变。再次点击输入框仍可正常弹出键盘。
        root.setFocusableInTouchMode(true);
        root.requestFocus();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            Log.d("OSTPICK_KEY", "keyCode=" + event.getKeyCode()
                    + ", scanCode=" + event.getScanCode());
        }
        if (event.getKeyCode() == 293) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                hideKeyboardForHardwareScan();
            }
            // 293 只收起键盘，消费按下和松开事件，不触发扫描或保存。
            return true;
        }
        if (event.getKeyCode() == 139) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                hideKeyboardForHardwareScan();
                if (currentPage == 0 || currentPage == 2) startHardwareScan();
            }
            if (currentPage == 0 || currentPage == 2) return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        activityResumed = true;
        openHardwareScanner();
    }

    @Override
    protected void onPause() {
        activityResumed = false;
        releaseHardwareScanner();
        dismissCandidates();
        clearPending();
        showMatchResult("等待扫描", "#E2E8F0");
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putInt("currentPage", currentPage);
        if (inputLocation != null) {
            outState.putString("collectLocation", inputLocation.getText().toString());
        }
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        sheetExecutor.shutdownNow();
        activityResumed = false;
        releaseHardwareScanner();
        if (tone != null) { tone.release(); tone = null; }
        if (database != null) database.close();
        super.onDestroy();
    }

    private void clearPending() {
        pendingId = -1;
        pendingNumber = null;
        pendingLocation = "";
        if (textPending != null) textPending.setText("待确认单号：无");
    }

    private void dismissCandidates() {
        if (candidateDialog != null) {
            candidateDialog.dismiss();
            candidateDialog = null;
        }
    }

    private void showMatchResult(String text, String background) {
        textMatchResult.setText(text);
        textMatchResult.setBackgroundColor(Color.parseColor(background));
        textMatchResult.setTextColor(Color.parseColor("#183153"));
    }

    private void showScannerStatus(String message, boolean success) {
        if (currentPage == 0) {
            showMatchResult(message, success ? "#E2E8F0" : "#FEE2E2");
        } else {
            showCollectResult(message, success);
        }
    }

    private void playSuccessTone() {
        if (tone != null) {
            try {
                tone.startTone(ToneGenerator.TONE_PROP_ACK, 200);
            } catch (RuntimeException error) {
                Log.w(SCAN_TAG, "播放提示音失败", error);
            }
        }
    }

    private void submitMatchInput() {
        stopHardwareScan();
        dismissCandidates();
        String barcode = inputMatch.getText().toString().trim();
        inputMatch.setText("");
        if (barcode.isEmpty()) {
            clearPending();
            showMatchResult("请输入或扫描箱单", "#FEE2E2");
            return;
        }
        if ("DONE".equalsIgnoreCase(barcode)) {
            completePendingOrder();
            return;
        }
        // 每次新箱单替换旧的待确认对象，包括未匹配和多条匹配。
        clearPending();
        try {
            List<OrderDbHelper.Order> all = database.getAll();
            List<OrderDbHelper.Order> candidates = new ArrayList<>();
            for (OrderDbHelper.Order order : all) {
                if (barcode.equals(order.number)) {
                    selectMatchedOrder(order);
                    return;
                }
                if (barcode.contains(order.number)) candidates.add(order);
            }
            if (candidates.isEmpty()) {
                showMatchResult("未匹配到基础单号", "#FEE2E2");
            } else if (candidates.size() == 1) {
                selectMatchedOrder(candidates.get(0));
            } else {
                showMatchResult("匹配到多条单号，请选择", "#FEF3C7");
                String[] labels = new String[candidates.size()];
                for (int i = 0; i < candidates.size(); i++) {
                    OrderDbHelper.Order order = candidates.get(i);
                    labels[i] = order.number + " · 库位 " + displayLocation(order.location)
                            + (order.done ? "（已完成）" : "（未完成）");
                }
                candidateDialog = new AlertDialog.Builder(this)
                        .setTitle("请选择本次操作的单号")
                        .setItems(labels, (dialog, which) -> {
                            if (activityResumed && currentPage == 0) {
                                selectMatchedOrder(candidates.get(which));
                            }
                            candidateDialog = null;
                        })
                        .setNegativeButton("取消", (dialog, which) -> {
                            clearPending();
                            showMatchResult("已取消，请重新扫描箱单", "#E2E8F0");
                            candidateDialog = null;
                        })
                        .setOnCancelListener(dialog -> {
                            clearPending();
                            showMatchResult("已取消，请重新扫描箱单", "#E2E8F0");
                            candidateDialog = null;
                        }).create();
                candidateDialog.show();
            }
        } catch (SQLiteException error) {
            clearPending();
            Log.e(SCAN_TAG, "匹配查询失败", error);
            showMatchResult("读取单号失败，请重试", "#FEE2E2");
        }
    }

    private void selectMatchedOrder(OrderDbHelper.Order selected) {
        clearPending();
        try {
            // 弹窗选择后重新读取，使用最新库位及完成状态。
            OrderDbHelper.Order order = database.getById(selected.id);
            if (order == null) {
                showMatchResult("单号已被删除，请重新扫描", "#FEE2E2");
                return;
            }
            String summary = locationSummary(order.location, !order.done);
            if (order.done) {
                showMatchResult("该单号已完成\n" + order.number + "\n" + summary, "#FEF3C7");
                return;
            }
            pendingId = order.id;
            pendingNumber = order.number;
            pendingLocation = order.location;
            textPending.setText("待确认单号：" + order.number
                    + "\n库位：" + displayLocation(order.location));
            showMatchResult("匹配成功\n" + order.number + "\n" + summary
                    + "\n请扫描 DONE 确认", "#DCFCE7");
            playSuccessTone();
        } catch (SQLiteException error) {
            clearPending();
            showMatchResult("读取库位信息失败，请重新扫描", "#FEE2E2");
        }
    }

    private String locationSummary(String location, boolean includesCurrent) {
        if (location == null || location.isEmpty()) {
            return "库位：未设置\n请在列表中补充库位，暂不统计同库位单号";
        }
        int remaining = database.countPendingAtLocation(location);
        return "库位：" + location + "\n该库位未完成：" + remaining + " 条"
                + (includesCurrent ? "（含当前单号）" : "")
                + (remaining == 0 ? "\n该库位已全部完成，可转移库位" : "");
    }

    private void completePendingOrder() {
        if (pendingId < 0 || pendingNumber == null) {
            showMatchResult("请先扫描并匹配箱单", "#FEE2E2");
            return;
        }
        String number = pendingNumber;
        String location = pendingLocation;
        boolean changed;
        try {
            String completedAt = new SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
            changed = database.markDone(pendingId, number, location, completedAt);
        } catch (SQLiteException error) {
            clearPending();
            Log.e(SCAN_TAG, "完成状态保存失败", error);
            showMatchResult("保存失败，请重新扫描箱单后确认", "#FEE2E2");
            return;
        }
        clearPending();
        if (!changed) {
            showMatchResult("单号、库位或完成状态已变化，请重新扫描箱单", "#FEF3C7");
            return;
        }
        // 完成写入与统计查询分开处理，避免统计失败时误报保存失败。
        String summary;
        try {
            summary = locationSummary(location, false);
        } catch (SQLiteException error) {
            summary = "库位：" + displayLocation(location) + "\n剩余数量暂时读取失败";
        }
        showMatchResult("操作完成 DONE\n" + number + "\n" + summary, "#DCFCE7");
        playSuccessTone();
        refreshOrders();
    }

    private String displayLocation(String location) {
        return location == null || location.isEmpty() ? "未设置" : location;
    }

    private String validateLocation(String location) {
        if (location.contains("\n") || location.contains("\r") || location.indexOf('\0') >= 0) {
            return "库位必须为单行文字";
        }
        return null;
    }

    private void setupLocationInput(Bundle savedInstanceState) {
        LinearLayout parent = (LinearLayout) inputCollect.getParent();
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        TextView label = new TextView(this);
        label.setText("确认库位后点击加入；同号覆盖库位，留空会清除旧库位");
        label.setTextSize(13);
        panel.addView(label);
        inputLocation = new EditText(this);
        inputLocation.setSingleLine(true);
        inputLocation.setInputType(InputType.TYPE_CLASS_TEXT);
        inputLocation.setHint("例如 A-01-02");
        inputLocation.setMinHeight(dp(48));
        if (savedInstanceState != null) {
            inputLocation.setText(savedInstanceState.getString("collectLocation", ""));
        }
        panel.addView(inputLocation, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        parent.addView(panel, parent.indexOfChild(inputCollect) + 1,
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void setupGoogleSheetImport() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(0, dp(24), 0, dp(16));
        TextView title = new TextView(this);
        title.setText("Google Sheet 采集");
        title.setTextSize(20);
        title.setTextColor(Color.parseColor("#183153"));
        panel.addView(title);

        sheetAddress = new EditText(this);
        sheetAddress.setHint("输入公开的 Google Sheet 链接");
        sheetAddress.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        sheetAddress.setSingleLine(true);
        sheetAddress.setMinHeight(dp(56));
        sheetAddress.setText(getSharedPreferences("ostpick_settings", MODE_PRIVATE)
                .getString("sheet_url", DEFAULT_SHEET_URL));
        panel.addView(sheetAddress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        skipSheetHeader = new CheckBox(this);
        skipSheetHeader.setText("跳过第一行（B 列有表头时勾选）");
        skipSheetHeader.setChecked(getSharedPreferences("ostpick_settings", MODE_PRIVATE)
                .getBoolean("skip_sheet_header", false));
        panel.addView(skipSheetHeader);

        importSheetButton = new Button(this);
        importSheetButton.setText("一键采集单号和库位");
        panel.addView(importSheetButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        sheetStatus = new TextView(this);
        sheetStatus.setText("按同一行采集 B 列单号和 D 列库位；已有单号补充空库位，保留 DONE 状态。");
        sheetStatus.setTextSize(15);
        panel.addView(sheetStatus);

        LinearLayout parent = (LinearLayout) textCollectResult.getParent();
        parent.addView(panel, parent.indexOfChild(textCollectResult) + 1,
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
        importSheetButton.setOnClickListener(view -> importGoogleSheet());
    }

    private void setSheetImportBusy(boolean busy) {
        googleImportRunning = busy;
        importSheetButton.setEnabled(!busy);
        importSheetButton.setText(busy ? "正在采集，请稍候……" : "一键采集单号和库位");
        sheetAddress.setEnabled(!busy);
        skipSheetHeader.setEnabled(!busy);
        inputCollect.setEnabled(!busy);
        inputLocation.setEnabled(!busy);
        findViewById(R.id.btnCollect).setEnabled(!busy);
        // 导入过程中避免并发删除、编辑和切换匹配对象。
        for (int i = 0; i < navigation.length; i++) {
            navigation[i].setEnabled(!busy && i != currentPage);
        }
    }

    private void importGoogleSheet() {
        if (googleImportRunning) return;
        final String address = sheetAddress.getText().toString().trim();
        final boolean skipHeader = skipSheetHeader.isChecked();
        try {
            GoogleSheetImporter.buildExportUrl(address);
        } catch (Exception error) {
            sheetAddress.setError(error.getMessage());
            return;
        }
        sheetAddress.setError(null);
        getSharedPreferences("ostpick_settings", MODE_PRIVATE).edit()
                .putString("sheet_url", address)
                .putBoolean("skip_sheet_header", skipHeader).apply();
        stopHardwareScan();
        setSheetImportBusy(true);
        sheetStatus.setTextColor(Color.parseColor("#183153"));
        sheetStatus.setText("正在下载表格并读取 B 列单号、D 列库位……");
        final Context appContext = getApplicationContext();
        sheetExecutor.execute(() -> {
            try {
                List<GoogleSheetImporter.SheetRow> values = GoogleSheetImporter.downloadRows(address);
                if (Thread.currentThread().isInterrupted()) return;
                OrderDbHelper.ImportResult result;
                // 后台任务拥有独立 helper，避免 Activity 销毁时关闭它正在使用的连接。
                OrderDbHelper importDb = new OrderDbHelper(appContext);
                try {
                    result = importDb.importRows(values, skipHeader);
                } finally {
                    importDb.close();
                }
                runOnUiThread(() -> {
                    if (isDestroyed() || isFinishing()) return;
                    setSheetImportBusy(false);
                    sheetStatus.setTextColor(Color.parseColor("#166534"));
                    sheetStatus.setText(result.summary());
                    if (values.isEmpty()) sheetStatus.append("\n表格为空，未添加数据。");
                    refreshOrders();
                });
            } catch (Exception error) {
                final String message;
                if (error instanceof UnknownHostException) {
                    message = "无法连接 Google，请检查设备网络是否能访问 Google Sheets";
                } else if (error instanceof SocketTimeoutException) {
                    message = "下载超时，请检查网络后重试";
                } else if (error instanceof SSLException) {
                    message = "HTTPS 连接失败，请检查设备日期、网络和系统证书";
                } else if (error instanceof SecurityException) {
                    message = "没有网络权限，请检查 AndroidManifest.xml 中的 INTERNET 权限";
                } else {
                    message = error.getMessage() == null ? "导入失败，请稍后重试" : error.getMessage();
                }
                runOnUiThread(() -> {
                    if (isDestroyed() || isFinishing()) return;
                    setSheetImportBusy(false);
                    sheetStatus.setTextColor(Color.parseColor("#B91C1C"));
                    sheetStatus.setText("采集失败：" + message + "\n本次未导入数据，原有数据保留。");
                });
            }
        });
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
