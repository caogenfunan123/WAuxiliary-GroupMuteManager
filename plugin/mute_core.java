// mute_core.java - 微信群免打扰读写（对齐 WeKit 的原生 DND setter 路径）
// 适配 com.tencent.mm 8.0.74 + WAux BeanShell 运行时。
//
// 依据（WeKit app/src/main/java/dev/ujhhgtg/wekit/features/api/core/WeConversationApi.kt）：
//   免打扰状态 = 联系人 type 字段的一个位（c01.e2.P: (getType() & 512) != 0）
//   写入口 = ContactStorageLogic 里两个原生 DND setter（静态, 签名 (联系人stub, boolean)）：
//     开启: 日志串 "OpenImOpLogLogic OpenIMModContactMuteOplog username:%s switch add"
//     关闭: 日志串 "... switch cancel"
//   原生 setter 自己会写 DB + 刷新会话列表；此处再补一次会话变更通知 + 联系人 T 镜像。
// BeanShell 注意: lambda 调用链内不要重名局部变量。

long _GM_MUTE_BIT = 512L; // 联系人 type bit 512

Object _gmK4 = null;
Object _gmTaskMgr = null;
Object _gmTaskCls = null;
Object _gmCSL = null;
Object _gmLoader = null;
Object _gmStorage = null;
java.lang.reflect.Method _gmDndAdd = null;
java.lang.reflect.Method _gmDndCancel = null;
String _gmRuntime = "未初始化";
boolean _gmBroken = false;

Object _gmLoad(String name) {
    return _gmLoader.loadClass(name);
}

boolean _gmParamOk(Class pt, Object arg) {
    if (arg == null) return !pt.isPrimitive();
    Class at = arg.getClass();
    if (pt.isAssignableFrom(at)) return true;
    if (pt == int.class && at == Integer.class) return true;
    if (pt == long.class && at == Long.class) return true;
    if (pt == boolean.class && at == Boolean.class) return true;
    if (pt == double.class && at == Double.class) return true;
    if (pt == float.class && at == Float.class) return true;
    if (pt == short.class && at == Short.class) return true;
    if (pt == byte.class && at == Byte.class) return true;
    if (pt == char.class && at == Character.class) return true;
    return false;
}

Object _gmCall(Object target, String name, Object[] args) {
    Class c = (target instanceof Class) ? (Class) target : target.getClass();
    boolean isStatic = (target instanceof Class);
    java.lang.reflect.Method fallback = null;
    while (c != null) {
        java.lang.reflect.Method[] all = c.getDeclaredMethods();
        for (int i = 0; i < all.length; i++) {
            java.lang.reflect.Method m = all[i];
            if (!m.getName().equals(name)) continue;
            Class[] pts = m.getParameterTypes();
            if (pts.length != args.length) continue;
            boolean ok = true;
            for (int j = 0; j < pts.length; j++) {
                if (!_gmParamOk(pts[j], args[j])) { ok = false; break; }
            }
            if (ok) {
                m.setAccessible(true);
                return m.invoke(isStatic ? null : target, args);
            }
            if (fallback == null) fallback = m;
        }
        c = c.getSuperclass();
    }
    if (fallback != null) {
        fallback.setAccessible(true);
        return fallback.invoke(isStatic ? null : target, args);
    }
    throw new NoSuchMethodException(name + "/" + args.length);
}

// 从 DexKit 命中结果里挑出目标静态方法（2 参数、void、第1参数可由 String 构造）
java.lang.reflect.Method _gmPickDnd(List members, String tag) {
    if (members == null || members.size() == 0) {
        log("[群免打扰] " + tag + " DexKit 无命中");
        return null;
    }
    java.lang.reflect.Method pick = null;
    for (int i = 0; i < members.size(); i++) {
        Object o = members.get(i);
        String info;
        if (o instanceof java.lang.reflect.Method) {
            java.lang.reflect.Method m = (java.lang.reflect.Method) o;
            Class[] pts = m.getParameterTypes();
            StringBuilder ps = new StringBuilder();
            for (int k = 0; k < pts.length; k++) {
                if (k > 0) ps.append(",");
                ps.append(pts[k].getSimpleName());
            }
            info = m.getDeclaringClass().getName() + "." + m.getName() + "(" + ps + ") static="
                 + java.lang.reflect.Modifier.isStatic(m.getModifiers());
            if (pick == null && pts.length == 2 && java.lang.reflect.Modifier.isStatic(m.getModifiers())) pick = m;
        } else {
            info = o.getClass().getName() + ":" + o;
        }
        log("[群免打扰] " + tag + " 候选[" + i + "] " + info);
    }
    if (pick != null) {
        log("[群免打扰] " + tag + " 选中: " + pick.getDeclaringClass().getName() + "." + pick.getName());
    }
    return pick;
}

void _gmInit() {
    if (_gmK4 != null || _gmBroken) return;
    try {
        // 1) 原生 DND setter：DexKit 按日志串锚定，同时拿微信 classloader
        List iAddMs = findMemberList(new String[]{"OpenImOpLogLogic OpenIMModContactMuteOplog username:%s switch add"});
        List iCancelMs = findMemberList(new String[]{"OpenImOpLogLogic OpenIMModContactMuteOplog username:%s switch cancel"});
        _gmDndAdd = _gmPickDnd(iAddMs, "DND-add");
        _gmDndCancel = _gmPickDnd(iCancelMs, "DND-cancel");
        if (_gmDndAdd != null) _gmCSL = _gmDndAdd.getDeclaringClass();
        if (_gmCSL != null) _gmLoader = _gmCSL.getClassLoader();
        if (_gmDndAdd == null && _gmDndCancel == null) throw new Exception("未找到原生 DND setter");

        // 2) 服务定位（loader 已就绪）
        Class iJ1c = (Class) _gmLoad("gm0.j1");
        Class iX3c = (Class) _gmLoad("vg3.x3");
        Object iSvc = _gmCall(iJ1c, "s", new Object[]{iX3c});
        if (iSvc == null) throw new Exception("微信内核服务未就绪");
        _gmK4 = _gmCall(iSvc, "Bi", new Object[]{});
        _gmTaskMgr = _gmCall(iSvc, "fj", new Object[]{});
        _gmTaskCls = _gmLoad("mn.a");
        _gmStorage = _gmCall(iSvc, "Di", new Object[]{});
        _gmRuntime = "dndAdd=" + (_gmDndAdd == null ? "null" : _gmDndAdd.getDeclaringClass().getName());
        log("[群免打扰] 初始化完成: " + _gmRuntime + " dndCancel=" + (_gmDndCancel == null ? "null" : "ok"));
        if (_gmK4 == null) throw new Exception("Bi() 返回 null");
    } catch (Throwable t) {
        _gmBroken = true;
        _gmRuntime = "解析失败: " + t;
        log("[群免打扰] 运行时解析失败: " + t);
    }
}

String _gmRuntimeInfo() {
    return _gmRuntime;
}

// ===== 读取：联系人 type bit 512 =====

Object _gmContact(String roomId) {
    return _gmCall(_gmK4, "n", new Object[]{roomId, Boolean.TRUE});
}

int _gmContactType(String roomId) {
    try {
        Object ctCon = _gmContact(roomId);
        if (ctCon == null) return -1;
        Object ctVal = _gmCall(ctCon, "getType", new Object[]{});
        if (ctVal instanceof Number) return ((Number) ctVal).intValue();
    } catch (Throwable ctErr) {
        log("[群免打扰] 读联系人 type 失败 " + roomId + ": " + ctErr);
    }
    return -1;
}

boolean _gmIsMuted(String roomId) {
    int mType = _gmContactType(roomId);
    if (mType >= 0) return (mType & 512) != 0;
    try {
        Object mP = _gmCall(_gmCSL, "P", new Object[]{_gmContact(roomId)});
        if (mP instanceof Boolean) return ((Boolean) mP).booleanValue();
    } catch (Throwable mErr) {}
    return false;
}

// ===== oplog 直发（CGI 681 modContact）：服务端同步，堵"闪一下被服务端冲掉" =====
// 依据: r45.h25.b() 自建 ReqResp(uri/cgiId 内置) + gm0.j1.d() 拿队列 + r1.h(scene,0)
// ops 容器 r45.c50 用 proto 基类 parseFrom 喂线格式字节(WeKit OpLogProto 结构):
//   list{1:count, 2:repeated op{1:cmdId, 2:buf{1:len, 2:bytes}}}

void _gmWVarint(ByteArrayOutputStream bo, int v) {
    while (true) {
        if ((v & ~0x7F) == 0) { bo.write(v); return; }
        bo.write((v & 0x7F) | 0x80);
        v >>>= 7;
    }
}

byte[] _gmBuildOplogBytes(byte[] payload) {
    ByteArrayOutputStream bufBo = new ByteArrayOutputStream();
    _gmWVarint(bufBo, payload.length);
    bufBo.write(0x12);
    _gmWVarint(bufBo, payload.length);
    bufBo.write(payload, 0, payload.length);
    byte[] opBuf = bufBo.toByteArray();

    ByteArrayOutputStream opBo = new ByteArrayOutputStream();
    opBo.write(0x08); _gmWVarint(opBo, 2);
    opBo.write(0x12); _gmWVarint(opBo, opBuf.length);
    opBo.write(opBuf, 0, opBuf.length);
    byte[] op = opBo.toByteArray();

    ByteArrayOutputStream listBo = new ByteArrayOutputStream();
    listBo.write(0x08); _gmWVarint(listBo, 1);
    listBo.write(0x12); _gmWVarint(listBo, op.length);
    listBo.write(op, 0, op.length);
    return listBo.toByteArray();
}

boolean _gmSendOplogMute(String roomId, boolean mute) {
    // 官方UI同款：oplog cmd20 notify任务 mn.a(roomId, g, 0)（RoomInfoDetailUI handler 实证）
    // g 极性(X6/Polarity)：g=0 → 免打扰开；g=1 → 免打扰关
    // type512/attrflag 由服务端处理完 cmd20 后随同步回来，本地立即态由原生 setter 保障
    try {
        Class oMac = (Class) _gmLoad("mn.a");
        java.lang.reflect.Constructor oMacCtor = oMac.getDeclaredConstructor(new Class[]{String.class, int.class, int.class});
        oMacCtor.setAccessible(true);
        Object oTask = oMacCtor.newInstance(new Object[]{roomId, new Integer(mute ? 0 : 1), new Integer(0)});
        Object oRes = _gmCall(_gmTaskMgr, "c", new Object[]{oTask});
        log("[群免打扰] cmd20任务 " + roomId + " mute=" + mute + " g=" + (mute ? 0 : 1) + " 入队=" + oRes);
        return true;
    } catch (Throwable oErr) {
        log("[群免打扰] cmd20任务失败(继续本地): " + oErr);
        return false;
    }
}

// ===== 写入：原生 DND setter =====

boolean _gmSetMute(String roomId, boolean mute) {
    java.lang.reflect.Method sM = mute ? _gmDndAdd : _gmDndCancel;
    if (sM == null) return false;
    boolean sOk = false;
    // 1) 原生 DND setter：e2.p0/z0 内部自己 Bi().n() 重载 contact → setType(±512) → g0()保存+推任务
    try {
        Class sP0 = sM.getParameterTypes()[0];
        Object sStub = null;
        try {
            java.lang.reflect.Constructor sCtor = sP0.getDeclaredConstructor(new Class[]{String.class});
            sCtor.setAccessible(true);
            sStub = sCtor.newInstance(new Object[]{roomId});
        } catch (Throwable sCtorErr) {
            Object sReal = _gmContact(roomId);
            if (sReal != null && sP0.isAssignableFrom(sReal.getClass())) sStub = sReal;
            else throw new Exception("stub 构造失败(" + sP0.getName() + "): " + sCtorErr);
        }
        sM.setAccessible(true);
        sM.invoke(null, new Object[]{sStub, Boolean.TRUE});
        sOk = true;
    } catch (Throwable sErr) {
        log("[群免打扰] 原生 DND 调用失败 " + roomId + " mute=" + mute + ": " + sErr);
    }
    // 2) 强制持久化 type + T 到 contact：Bi().n(true) 会回源 DB，原生 setter 的缓存写可能不回填 type 列
    //    T 极性（RoomInfoDetailUI.X6 实证）：T=0 → 免打扰开(checkbox checked)；T=1 → 免打扰关
    try {
        Object sC = _gmContact(roomId);
        if (sC != null) {
            Object sTv = _gmCall(sC, "getType", new Object[]{});
            int sT = (sTv instanceof Number) ? ((Number) sTv).intValue() : 0;
            _gmCall(sC, "setType", new Object[]{new Integer(mute ? (sT | 512) : (sT & -513))});
            _gmCall(sC, "J2", new Object[]{new Integer(mute ? 0 : 1)});
            Object sWr = _gmCall(_gmK4, "p0", new Object[]{roomId, sC});
            log("[群免打扰] type/T持久化 " + roomId + " type " + sT + "->" + (mute ? (sT | 512) : (sT & -513)) + " T=" + (mute ? 0 : 1) + " 结果=" + sWr);
        }
    } catch (Throwable sTypeErr) {
        log("[群免打扰] type/T 持久化失败(继续): " + sTypeErr);
    }
    // 3) attrflag 0x200000：w3/s2/e2 红点与通知抑制读它（m4.Z 原生 SQL+通知）
    try {
        Object sConv = _gmCall(_gmStorage, "p", new Object[]{roomId});
        if (sConv != null) {
            Object sCurV = _gmCall(sConv, "u0", new Object[]{});
            int sCur = (sCurV instanceof Number) ? ((Number) sCurV).intValue() : 0;
            _gmCall(_gmStorage, "Z", new Object[]{roomId, new Integer(2097152), Boolean.valueOf(mute), new Integer(sCur)});
        }
    } catch (Throwable sAttrErr) {
        log("[群免打扰] attrflag 写入失败(继续): " + sAttrErr);
    }
    // 2.5) oplog 直发（服务端同步——不直发会被服务端状态冲掉还原）
    _gmSendOplogMute(roomId, mute);
    // 3) SharedPreferences room_msg_notify：RoomInfoDetailUI X6 首次加载读它决定开关显示
    try {
        Object sCtx = _gmCall((Class) _gmLoad("com.tencent.mm.sdk.platformtools.x2"), "c", new Object[]{});
        // x2.a 是 Application context 静态字段，直接取
        Class sX2 = (Class) _gmLoad("com.tencent.mm.sdk.platformtools.x2");
        Class sVoid = null;
        Object sAppCtx = null;
        java.lang.reflect.Field[] sFds = sX2.getDeclaredFields();
        for (int sFi = 0; sFi < sFds.length; sFi++) {
            if (sFds[sFi].getType().getName().equals("android.app.Application")
                || sFds[sFi].getType().getName().equals("android.content.Context")
                || sFds[sFi].getType().getName().equals("android.content.ContextWrapper")) {
                sFds[sFi].setAccessible(true);
                sAppCtx = sFds[sFi].get(null);
                if (sAppCtx != null) break;
            }
        }
        if (sAppCtx != null) {
            String sPkg = (String) _gmCall(sAppCtx, "getPackageName", new Object[]{});
            Object sSp = _gmCall(sAppCtx, "getSharedPreferences", new Object[]{sPkg + "_preferences", new Integer(0)});
            Object sEd = _gmCall(sSp, "edit", new Object[]{});
            // X6: g==0(T=0,免打扰开) → putBoolean(true)；g==1(T=1,未免打扰) → putBoolean(false)
            _gmCall(sEd, "putBoolean", new Object[]{"room_msg_notify", Boolean.valueOf(mute)});
            Object sCm = _gmCall(sEd, "commit", new Object[]{});
            log("[群免打扰] SP room_msg_notify 写入=" + sCm + "(mute=" + mute + ")");
        } else {
            log("[群免打扰] 未拿到 Application context");
        }
    } catch (Throwable sSpErr) {
        log("[群免打扰] SP 写入失败(继续): " + sSpErr);
    }
    // 4) 会话列表刷新 + 回读验证
    try {
        _gmCall(_gmStorage, "b", new Object[]{new Integer(3), _gmStorage, roomId});
    } catch (Throwable sNotifyErr) {}
    int sAfter = _gmContactType(roomId);
    boolean sPersisted = sAfter >= 0 && (((sAfter & 512) != 0) == mute);
    log("[群免打扰] 写入 " + roomId + " mute=" + mute + " 原生=" + sOk + " type回读=" + sAfter + " 生效=" + sPersisted);
    return sPersisted;
}

String _gmToggleOne(String roomId) {
    _gmInit();
    if (_gmBroken) return "微信版本不兼容，无法操作";
    try {
        boolean oCur = _gmIsMuted(roomId);
        boolean oDone = _gmSetMute(roomId, !oCur);
        if (!oDone) return "写入未生效（看日志）";
        return oCur ? "已关闭免打扰（通知开启）" : "已开启免打扰";
    } catch (Throwable oErr) {
        log("[群免打扰] 切换异常 " + roomId + ": " + oErr);
        return "切换失败: " + oErr;
    }
}

String _gmToggleBatch(ArrayList ids, boolean want) {
    _gmInit();
    if (_gmBroken) return "微信版本不兼容，无法操作";
    int bOk = 0;
    int bFail = 0;
    for (int bI = 0; bI < ids.size(); bI++) {
        String bId = (String) ids.get(bI);
        try {
            if (_gmSetMute(bId, want)) bOk = bOk + 1; else bFail = bFail + 1;
        } catch (Throwable bErr) {
            log("[群免打扰] 批量异常 " + bId + ": " + bErr);
            bFail = bFail + 1;
        }
    }
    String bMode = want ? "开启" : "关闭";
    log("[群免打扰] 批量" + bMode + "完成: 成功" + bOk + " 失败" + bFail);
    return "批量" + bMode + "：生效 " + bOk + "，失败 " + bFail;
}

// 整行列导出（原生切换前后对比用：哪列在动=真存储）
void _gmDumpRow(String tag, String table, String roomId) {
    try {
        Object db = _gmDb();
        if (db == null) return;
        Object cur = _gmCall(db, "f", new Object[]{"select * from " + table + " where username='" + roomId + "'", null, new Integer(2)});
        if (cur == null) return;
        try {
            Object hasNext = _gmCall(cur, "moveToNext", new Object[]{});
            if (!(hasNext instanceof Boolean) || !((Boolean) hasNext).booleanValue()) {
                log("[DUMP-" + tag + "] " + roomId + " 无行");
                return;
            }
            int cn = ((Number) _gmCall(cur, "getColumnCount", new Object[]{})).intValue();
            StringBuilder sb = new StringBuilder("[DUMP-" + tag + "] " + roomId + " ");
            for (int i = 0; i < cn; i++) {
                if (i > 0) sb.append(" | ");
                Object name = _gmCall(cur, "getColumnName", new Object[]{new Integer(i)});
                Object val = null;
                try { val = _gmCall(cur, "getString", new Object[]{new Integer(i)}); } catch (Throwable vErr) {}
                sb.append(name).append("=").append(val);
            }
            log(sb.toString());
        } finally {
            try { _gmCall(cur, "close", new Object[]{}); } catch (Throwable cErr) {}
        }
    } catch (Throwable dErr) {
        log("[DUMP-" + tag + "] " + roomId + " ERR:" + dErr);
    }
}

// ===== 诊断（调用方负责放到后台线程）=====
void _gmDiagnose(String roomId) {
    StringBuilder dSb = new StringBuilder("[诊断] " + roomId);
    try {
        int dType = _gmContactType(roomId);
        dSb.append(" type=").append(dType).append("(512:").append(dType >= 0 && (dType & 512) != 0 ? "1" : "0").append(")");
    } catch (Throwable dE1) {
        dSb.append(" type=ERR:").append(dE1.getMessage());
    }
    try {
        Object dCon = _gmContact(roomId);
        dSb.append(" T=").append(dCon == null ? "null" : dCon.getClass().getField("T").getInt(dCon));
    } catch (Throwable dE2) {
        dSb.append(" T=ERR:").append(dE2.getMessage());
    }
    try {
        Object dP = _gmCall(_gmCSL, "P", new Object[]{_gmContact(roomId)});
        dSb.append(" e2.P=").append(dP);
    } catch (Throwable dE3) {
        dSb.append(" P=ERR:").append(dE3.getMessage());
    }
    log(dSb.toString());
    _gmDumpRow("R", "rcontact", roomId);
    _gmDumpRow("C", "rconversation", roomId);
}
