// 群免打扰管家 - 入口（BeanShell 顶层脚本）
// 微信 8.0.74 + WAux 运行时。免打扰 = rconversation.attrflag 0x200000 + 联系人 T。
// BeanShell 注意: lambda 调用链内不要重名局部变量。

void onLoad() {
    try {
        loadJava("mute_core.java");
        loadJava("mute_ui.java");
        _gmInit();
        log("群免打扰管家加载完成: " + _gmRuntimeInfo());
        // oplog RunCgi 直发自检：真切换一个群（状态改变，验证服务端是否认账）
        new Thread(new Runnable() {
            public void run() {
                try { Thread.sleep(6000); } catch (Throwable stErr) {}
                try {
                    String stRoom = "53643948731@chatroom";
                    boolean stCur = _gmIsMuted(stRoom);
                    boolean stWant = true;   // 目标态：静音（幂等，重复执行不翻转）
                    log("[群免打扰] 自检复查 " + stRoom + " 当前=" + stCur + " 目标=" + stWant);
                    if (stCur != stWant) {
                        boolean stOk = _gmSetMute(stRoom, stWant);
                        log("[群免打扰] 自检结束 结果=" + stOk);
                    } else {
                        log("[群免打扰] 自检结束 状态已保持(未被冲掉)");
                    }
                } catch (Throwable stErr2) {
                    log("[群免打扰] 自检异常: " + stErr2);
                }
            }
        }).start();
    } catch (Throwable t) {
        log("onLoad 失败: " + t);
    }
}

void onUnload() {
    // 未注册 Hook / 广播 / 定时任务，无需清理
}

// 指令：任意聊天界面（群聊/私聊）输入并发送「群聊免打扰」
// → 拦截该消息并打开本插件的设置界面（搜索面板/免打扰管理）
boolean onClickSendBtn(String text) {
    try {
        if (text == null) return false;
        String sTxt = text.trim();
        if (sTxt.length() == 0) return false;
        if (!sTxt.equals("群聊免打扰")) return false;
        log("[群免打扰] 指令触发，打开插件设置界面 talker=" + getTargetTalker());
        openSettings();
        return true;
    } catch (Throwable sErr) {
        log("[群免打扰] 指令打开设置界面失败: " + sErr);
        return false;
    }
}

void openSettings() {
    try {
        _gmShowSearchDialog();
    } catch (Throwable t) {
        log("openSettings 失败: " + t);
        toast("打开失败: " + t);
    }
}

void onCreateHomePopMenu() {
    addHomePopMenuItem("群免打扰管家", "info", () -> {
        try {
            _gmShowSearchDialog();
        } catch (Throwable t) {
            log("打开搜索失败: " + t);
            toast("打开失败: " + t);
        }
    });
}

void onCreateConversationItemMenu(Object conversationBean) {
    addConversationItemMenuItem("免打扰开关", mConv -> {
        try {
            log("[群免打扰] 会话菜单点击, bean=" + (mConv == null ? "null" : mConv.getClass().getName()));
            if (mConv == null) { toast("会话数据为空"); return; }
            Object mUser = null;
            try {
                mUser = mConv.getClass().getMethod("getUsername", new Class[0]).invoke(mConv, new Object[0]);
            } catch (Throwable mErr) {
                log("[群免打扰] getUsername 反射失败: " + mErr);
            }
            log("[群免打扰] username = " + mUser);
            if (mUser == null || !(mUser instanceof String) || !((String) mUser).endsWith("@chatroom")) {
                toast("仅支持群聊会话");
                return;
            }
            Object mRes = _gmToggleOne((String) mUser);
            log("[群免打扰] 会话菜单切换结果: " + mRes);
            toast("" + mRes);
        } catch (Throwable mErr2) {
            log("[群免打扰] 会话菜单切换失败: " + mErr2);
            toast("切换失败: " + mErr2);
        }
    });
}
