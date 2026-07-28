<script setup>
import { ref, onMounted, onBeforeUnmount, watch } from 'vue'
import { EditorView, basicSetup } from 'codemirror'
import { java } from '@codemirror/lang-java'
import { python } from '@codemirror/lang-python'
import { oneDark } from '@codemirror/theme-one-dark'
import { EditorState } from '@codemirror/state'

const props = defineProps({
  modelValue: { type: String, default: '' },
  language: { type: String, default: 'java' },
  readonly: { type: Boolean, default: false }
})

const emit = defineEmits(['update:modelValue'])

const container = ref(null)
let view = null

function langExtension() {
  return props.language === 'python' ? python() : java()
}

function createState(doc) {
  return EditorState.create({
    doc,
    extensions: [
      basicSetup,
      oneDark,
      langExtension(),
      EditorView.editable.of(!props.readonly),
      EditorView.updateListener.of((update) => {
        if (update.docChanged) {
          emit('update:modelValue', update.state.doc.toString())
        }
      }),
      EditorView.theme({
        '&': { height: '100%', fontSize: '13px' },
        '.cm-scroller': { overflow: 'auto', fontFamily: '"JetBrains Mono", "Fira Code", Consolas, monospace' },
        '.cm-content': { minHeight: '200px', padding: '12px 0' }
      })
    ]
  })
}

onMounted(() => {
  view = new EditorView({
    state: createState(props.modelValue),
    parent: container.value
  })
})

watch(() => props.modelValue, (newVal) => {
  if (view && newVal !== view.state.doc.toString()) {
    view.dispatch({
      changes: { from: 0, to: view.state.doc.length, insert: newVal }
    })
  }
})

watch(() => props.language, () => {
  if (view) {
    const doc = view.state.doc.toString()
    view.destroy()
    view = new EditorView({
      state: createState(doc),
      parent: container.value
    })
  }
})

onBeforeUnmount(() => {
  if (view) view.destroy()
})
</script>

<template>
  <div ref="container" class="cm-editor-container"></div>
</template>

<style scoped>
.cm-editor-container {
  border: 1px solid #334155;
  border-radius: 10px;
  overflow: hidden;
  min-height: 240px;
  max-height: 480px;
}

.cm-editor-container :deep(.cm-editor) {
  height: 100%;
}

.cm-editor-container :deep(.cm-editor.cm-focused) {
  outline: none;
  border-color: #3b82f6;
  box-shadow: 0 0 0 3px rgba(59, 130, 246, 0.15);
}
</style>
