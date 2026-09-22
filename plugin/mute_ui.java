// mute_ui.java - 搜索 + 多选批量切换对话框（纯 Android UI，运行在微信进程）
// 点击行 = 选中/取消；长按行 = 立即切换单个；按钮 = 批量操作
import android.app.AlertDialog;
import android.app.Activity;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

ArrayList _gmAllIds = null;
ArrayList _gmAllNames = null;
ArrayList _gmShown = null;    // 过滤后展示的下标
ArrayList _gmSelected = null; // 已选群 id
ArrayAdapter _gmAdapter = null;
EditText _gmSearch = null;
Object _gmDialog = null;

String _gmRowText(int idx) {
    String name = (String) _gmAllNames.get(idx);
    String id = (String) _gmAllIds.get(idx);
    String mark = _gmSelected.contains(id) ? "[√]" : "[ ]";
    String st;
    try {
        st = _gmIsMuted(id) ? "已免打扰" : "通知中";
    } catch (Throwable t) {
        st = "未知";
    }
    return mark + " [" + st + "] " + name + "\n" + id;
}

void _gmLoadGroups() {
    _gmAllIds = new ArrayList();
    _gmAllNames = new ArrayList();
    List groups = null;
    try {
        groups = getGroupList();
    } catch (Throwable t) {
        log("[群免打扰] 获取群列表失败: " + t);
        return;
    }
    if (groups == null) {
        log("[群免打扰] getGroupList() 返回 null");
        return;
    }
    for (int i = 0; i < groups.size(); i++) {
        Object g = groups.get(i);
        if (g == null) continue;
        try {
            String id = (String) g.getClass().getMethod("getRoomId", new Class[0]).invoke(g, new Object[0]);
            String name = (String) g.getClass().getMethod("getName", new Class[0]).invoke(g, new Object[0]);
            if (id == null || id.length() == 0) continue;
            if (name == null || name.length() == 0) name = id;
            _gmAllIds.add(id);
            _gmAllNames.add(name);
        } catch (Throwable t) {
            log("[群免打扰] 群信息反射解析失败: " + t);
        }
    }
}

String _gmKey(int idx) {
    String name = (String) _gmAllNames.get(idx);
    String id = (String) _gmAllIds.get(idx);
    String s = (name == null ? "" : name) + " " + (id == null ? "" : id);
    return s.toLowerCase();
}

void _gmApplyFilter(String kw) {
    _gmShown = new ArrayList();
    String k = (kw == null) ? "" : kw.trim().toLowerCase();
    for (int i = 0; i < _gmAllIds.size(); i++) {
        if (k.length() == 0 || _gmKey(i).indexOf(k) >= 0) _gmShown.add(new Integer(i));
    }
    if (_gmAdapter != null) {
        _gmAdapter.clear();
        for (int j = 0; j < _gmShown.size(); j++) {
            _gmAdapter.add(_gmRowText(((Integer) _gmShown.get(j)).intValue()));
        }
        _gmAdapter.notifyDataSetChanged();
    }
}

Button _gmBtn(Activity act, String label) {
    Button b = new Button(act);
    b.setText(label);
    b.setTextSize(13.0f);
    return b;
}

void _gmRunBatch(final boolean want) {
    if (_gmSelected == null || _gmSelected.isEmpty()) {
        toast("请先点按列表项勾选群聊");
        return;
    }
    final ArrayList targets = new ArrayList(_gmSelected);
    final Activity act = getTopActivity();
    toast("开始处理 " + targets.size() + " 个群聊...");
    Thread t = new Thread(new Runnable() {
        public void run() {
            final String result = _gmToggleBatch(targets, want);
            try {
                if (act != null) {
                    act.runOnUiThread(new Runnable() {
                        public void run() {
                            toast(result);
                            try {
                                String kw = _gmSearch != null ? _gmSearch.getText().toString() : "";
                                _gmApplyFilter(kw);
                            } catch (Throwable ignore) {}
                        }
                    });
                }
            } catch (Throwable tt) {
                log("[群免打扰] UI 回调失败: " + tt);
            }
        }
    });
    t.start();
}

void _gmShowSearchDialog() {
    _gmInit();
    if (_gmBroken) {
        toast("微信内核服务未就绪: " + _gmRuntime);
        return;
    }
    Activity act = getTopActivity();
    if (act == null) {
        toast("请在微信界面内点击打开");
        return;
    }
    if (_gmSelected == null) _gmSelected = new ArrayList();
    _gmLoadGroups();
    log("[群免打扰] 打开搜索面板, 加载到群聊总数: " + _gmAllIds.size());
    // 诊断 dump 放后台线程（在 UI 线程循环查库会卡死微信）
    final ArrayList diagIds = new ArrayList(_gmAllIds);
    new Thread(new Runnable() {
        public void run() {
            for (int di = 0; di < diagIds.size(); di++) {
                try { _gmDiagnose((String) diagIds.get(di)); } catch (Throwable ignore) {}
            }
        }
    }).start();

    LinearLayout box = new LinearLayout(act);
    box.setOrientation(LinearLayout.VERTICAL);
    float den = act.getResources().getDisplayMetrics().density;
    int pad = (int) (12 * den);
    box.setPadding(pad, pad, pad, pad);

    _gmSearch = new EditText(act);
    _gmSearch.setHint("输入群名或群ID搜索（点按勾选，长按单个切换）");
    _gmSearch.setSingleLine(true);
    box.addView(_gmSearch);

    LinearLayout btnRow = new LinearLayout(act);
    btnRow.setOrientation(LinearLayout.HORIZONTAL);
    Button bAll = _gmBtn(act, "全选");
    Button bNone = _gmBtn(act, "清空");
    Button bOn = _gmBtn(act, "开启免打扰");
    Button bOff = _gmBtn(act, "关闭免打扰");
    btnRow.addView(bAll, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
    btnRow.addView(bNone, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
    btnRow.addView(bOn, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
    btnRow.addView(bOff, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
    box.addView(btnRow);

    ListView lv = new ListView(act);
    _gmAdapter = new ArrayAdapter(act, android.R.layout.simple_list_item_1, new ArrayList());
    lv.setAdapter(_gmAdapter);
    box.addView(lv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f));

    bAll.setOnClickListener(new View.OnClickListener() {
        public void onClick(View v) {
            try {
                for (int j = 0; j < _gmShown.size(); j++) {
                    String id = (String) _gmAllIds.get(((Integer) _gmShown.get(j)).intValue());
                    if (!_gmSelected.contains(id)) _gmSelected.add(id);
                }
                _gmApplyFilter(_gmSearch.getText().toString());
            } catch (Throwable t) { log("[群免打扰] 全选失败: " + t); }
        }
    });
    bNone.setOnClickListener(new View.OnClickListener() {
        public void onClick(View v) {
            try {
                _gmSelected.clear();
                _gmApplyFilter(_gmSearch.getText().toString());
            } catch (Throwable t) { log("[群免打扰] 清空失败: " + t); }
        }
    });
    bOn.setOnClickListener(new View.OnClickListener() {
        public void onClick(View v) { _gmRunBatch(true); }
    });
    bOff.setOnClickListener(new View.OnClickListener() {
        public void onClick(View v) { _gmRunBatch(false); }
    });

    lv.setOnItemClickListener(new AdapterView.OnItemClickListener() {
        public void onItemClick(AdapterView parent, View view, int position, long rowId) {
            try {
                int realIdx = ((Integer) _gmShown.get(position)).intValue();
                String id = (String) _gmAllIds.get(realIdx);
                if (_gmSelected.contains(id)) {
                    _gmSelected.remove(id);
                } else {
                    _gmSelected.add(id);
                }
                _gmApplyFilter(_gmSearch.getText().toString());
            } catch (Throwable t) {
                log("[群免打扰] 点击勾选失败: " + t);
            }
        }
    });
    lv.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
        public boolean onItemLongClick(AdapterView parent, View view, int position, long rowId) {
            try {
                int idx = ((Integer) _gmShown.get(position)).intValue();
                String name = (String) _gmAllNames.get(idx);
                String id = (String) _gmAllIds.get(idx);
                String r = _gmToggleOne(id);
                toast(name + "：" + r);
                _gmApplyFilter(_gmSearch.getText().toString());
                return true;
            } catch (Throwable t) {
                log("[群免打扰] 对话框长按切换失败: " + t);
                return true;
            }
        }
    });

    _gmSearch.addTextChangedListener(new TextWatcher() {
        public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
        public void onTextChanged(CharSequence s, int a, int b, int c) {}
        public void afterTextChanged(Editable s) {
            try { _gmApplyFilter(s.toString()); } catch (Throwable t) { log("[群免打扰] 过滤失败: " + t); }
        }
    });

    _gmApplyFilter("");

    AlertDialog d = new AlertDialog.Builder(act)
        .setTitle("群免打扰管家（共 " + _gmAllIds.size() + " 个群）")
        .setView(box)
        .setPositiveButton("关闭", null)
        .create();
    _gmDialog = d;
    d.show();
}
