# ---- WebView JS 桥接 ----
# EditorActivity.RichBridge 的方法通过 addJavascriptInterface 暴露给页面 JS 调用，
# 方法名就是 JS 与原生之间的调用协议，不能被重命名或移除。
# （同名的 RichBridge 类本身走对象实例传递，JS 侧不引用类名，无需 keep 类名。）
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# 富文本编辑器的桥接类由 WebView 持有引用，保留其方法签名
-keepclassmembers class com.htmlnotes.app.EditorActivity$RichBridge {
    public *;
}

# ---- 默认规则参考 ----
# 本项目其余部分（JSONObject 手写序列化、ViewBinding、无反射）无需额外 keep 规则。
