<template>
  <div class="mm-wrap">
    <svg ref="svgRef" class="mm-svg"></svg>
  </div>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount, watch } from 'vue'
import { Transformer } from 'markmap-lib'
import { Markmap } from 'markmap-view'

/**
 * 思维导图渲染：把后端返回的 Markdown 层级（# 根 / ## 章 / ### 节 / - 要点）
 * 用 markmap 渲染成可缩放、可折叠的树状图（SVG）。
 * 空内容时退回纯文本展示，避免白屏。
 */
const props = defineProps({
  content: { type: String, default: '' }
})

const svgRef = ref(null)
let mm = null
let ro = null

function render () {
  const el = svgRef.value
  if (!el) return
  const text = (props.content || '').trim()
  if (!text) return
  try {
    const { root } = new Transformer().transform(text)
    if (mm) {
      mm.setData(root)
      mm.fit()
    } else {
      // markmap 需要 SVG 有明确宽高；宽度 100%，高度由外层容器撑
      mm = Markmap.create(el, {
        autoFit: true,
        duration: 200,
        maxWidth: 280
      }, root)
      ro = new ResizeObserver(() => mm && mm.fit())
      ro.observe(el.parentElement)
    }
  } catch (e) {
    // markdown 无法解析时降级为文本（父组件已做，这里不再抛错）
  }
}

onMounted(render)
watch(() => props.content, render)
onBeforeUnmount(() => {
  ro && ro.disconnect()
  mm = null
})
</script>

<style scoped>
.mm-wrap {
  position: relative;
  width: 100%;
  height: 380px;
  min-height: 200px;
  border: 1px solid var(--el-border-color-lighter, #e4e7ed);
  border-radius: 8px;
  overflow: hidden;
  background: #fff;
  margin-top: 6px;
}
.mm-svg {
  display: block;
  width: 100%;
  height: 100%;
}
</style>
