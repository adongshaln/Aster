import io

path = 'app/src/main/java/com/adong/adchat/ui/clawd/ClawdBigView.kt'
with io.open(path, encoding='utf-8') as f:
    src = f.read()

old = '''    // 大爪原位 = 输入框右内缘（x=0 时左边贴右边）；x/fy 是相对这个位置的偏移（dy<0 往上升）。
    // 渲染 = 对齐到带右缘再平移：原版按钮钉在 #send_form 内右侧，x 是 translateX 偏移。
    // 触摸层：48x48dp Box，命中才消费
    Box(
        modifier
            .align(Alignment.TopEnd)
            .offset { IntOffset((dx * density).roundToInt(), (dy * density).roundToInt()) }
            .size(w.dp, h.dp)'''
new = '''    // 大爪原位 = 输入框右内缘（x=0 时左边贴右边）；x/fy 是相对这个位置的偏移（dy<0 往上升）。
    // 渲染 = 对齐到带右缘再平移：原版按钮钉在 #send_form 内右侧，x 是 translateX 偏移。
    // 触摸层：48x48dp Box，命中才消费
    Box(Modifier.fillMaxSize()) {
    Box(
        Modifier
            .align(Alignment.TopEnd)
            .offset { IntOffset((dx * density).roundToInt(), (dy * density).roundToInt()) }
            .size(w.dp, h.dp)'''
assert src.count(old) == 1, 'anchor'
src = src.replace(old, new, 1)

# The inner Box's closing is at end of function; add one more closing brace.
# Find last '    }\n}' pattern at file end
assert src.rstrip().endswith('}\n}')
src = src.rstrip()
src = src[:-2] + '    }\n}\n'
with io.open(path, 'w', encoding='utf-8', newline='\n') as f:
    f.write(src)
print('done')
