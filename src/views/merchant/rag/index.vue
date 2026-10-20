<template>
  <div class="app-container">
    <el-row :gutter="16">
      <!-- ==================== 左：知识库 ==================== -->
      <el-col :span="9">
        <el-card shadow="never">
          <template #header>
            <div class="card-head">
              <span>知识库</span>
              <el-tag :type="docExists ? 'success' : 'info'" size="small" effect="plain">
                {{ docExists ? '已入库' : '未入库' }}
              </el-tag>
            </div>
          </template>

          <el-form label-width="72px">
            <el-form-item label="文档名">
              <el-input
                v-model="docName"
                placeholder="例如 Day15"
                clearable
                @blur="handleCheckExists"
              />
            </el-form-item>

            <el-form-item label="文档">
              <el-upload
                ref="uploadRef"
                drag
                :auto-upload="false"
                :limit="1"
                accept=".md,.markdown,.txt"
                :file-list="fileList"
                :on-change="handleFileChange"
                :on-remove="handleFileRemove"
                :on-exceed="handleExceed"
              >
                <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
                <div class="el-upload__text">拖入 .md / .txt，或<em>点击选择</em></div>
                <template #tip>
                  <div class="el-upload__tip">只收文本格式，上限 512KB</div>
                </template>
              </el-upload>
            </el-form-item>

            <el-form-item>
              <el-button type="primary" :loading="uploading" @click="handleUpload">
                {{ uploading ? '入库中…' : (docExists ? '重新入库（覆盖）' : '上传并入库') }}
              </el-button>
              <el-button :disabled="!docExists" @click="handleDelete">删除该文档</el-button>
            </el-form-item>
          </el-form>

          <el-alert v-if="uploadResult" type="success" :closable="false" show-icon>
            <template #title>
              入库完成：{{ uploadResult.chunks }} 个块
            </template>
            <div class="meta">
              {{ uploadResult.fileName }} · {{ uploadResult.bytes }} 字节 · {{ uploadResult.chars }} 字符
            </div>
          </el-alert>
        </el-card>
      </el-col>

      <!-- ==================== 右：问答 ==================== -->
      <el-col :span="15">
        <el-card shadow="never">
          <template #header>
            <div class="card-head">
              <span>提问</span>
              <span class="hint">Ctrl + Enter 发送</span>
            </div>
          </template>

          <el-input
            v-model="question"
            type="textarea"
            :rows="2"
            resize="none"
            placeholder="例如：断点续传依靠哪三个要素？"
            @keydown.ctrl.enter.prevent="handleAsk"
          />
          <div class="samples">
            <span class="samples-label">试试：</span>
            <el-tag
              v-for="q in samples"
              :key="q"
              class="sample"
              effect="plain"
              @click="question = q"
            >{{ q }}</el-tag>
          </div>

          <div class="ask-bar">
            <el-button type="primary" :loading="asking" @click="handleAsk">
              {{ asking ? '思考中…' : '提问' }}
            </el-button>
          </div>

          <el-divider />

          <!-- 失败：error 字段有值 -->
          <el-alert v-if="answer.error" type="warning" :closable="false" show-icon>
            <template #title>{{ answer.error }}</template>
          </el-alert>

          <!-- 成功 -->
          <template v-else-if="answer.answer">
            <div class="answer-text" v-html="renderedAnswer"></div>

            <div v-if="compression" class="compression">原文压缩：{{ compression }}</div>

            <div class="cost">
              <el-tag size="small" type="info" effect="plain">
                命中 {{ answer.contextChunks }} 段
              </el-tag>
              <el-tag size="small" type="info" effect="plain">
                上下文 {{ answer.contextChars }} 字
              </el-tag>
              <el-tag size="small" type="info" effect="plain">
                引用 {{ answer.citations.length }} 条
              </el-tag>
            </div>

            <el-collapse class="cite-collapse">
              <el-collapse-item :title="`查看引用（${answer.citations.length}）`" name="cites">
                <div v-for="(c, i) in answer.citations" :key="c.chunkId" class="cite-item">
                  <div class="cite-head">
                    <span class="cite-no">#{{ i + 1 }}</span>
                    <span class="cite-score">相关度 {{ c.score.toFixed(4) }}</span>
                    <span class="cite-id">chunkId {{ c.chunkId }}</span>
                  </div>
                  <pre class="cite-snippet" v-html="renderRich(c.snippet)"></pre>
                </div>
              </el-collapse-item>
            </el-collapse>
          </template>

          <el-empty v-else description="还没有提问" :image-size="72" />
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup name="Rag">
import { ref, reactive, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { uploadDocument, askQuestion, existsDocument, delDocument } from '@/api/merchant/rag'

// ---------------- 知识库 ----------------
const docName = ref('')
const docExists = ref(false)
const uploading = ref(false)
const uploadResult = ref(null)
const uploadRef = ref(null)
const fileList = ref([])
const selectedFile = ref(null)

/** 选中文件后：记下 raw、自动用文件名当文档名、顺手查一下这个文档名在库里有没有 */
function handleFileChange(file, files) {
  fileList.value = files.slice(-1)
  selectedFile.value = file.raw
  if (!docName.value) {
    docName.value = stripExtension(file.name)
  }
  handleCheckExists()
}

function handleFileRemove() {
  fileList.value = []
  selectedFile.value = null
}

/** limit=1 时再选会被拦下，这里改成"替换掉原来那个" */
function handleExceed() {
  ElMessageBox.confirm('只能传一个文件，要替换成新选的吗？', '提示', { type: 'warning' })
    .then(() => {
      uploadRef.value.clearFiles()
      selectedFile.value = null
      fileList.value = []
    })
    .catch(() => {})
}

function stripExtension(fileName) {
  const dot = fileName.lastIndexOf('.')
  return dot <= 0 ? fileName : fileName.substring(0, dot)
}

async function handleCheckExists() {
  if (!docName.value) {
    docExists.value = false
    return
  }
  const res = await existsDocument(docName.value)
  docExists.value = res.data === true
}

async function handleUpload() {
  if (!selectedFile.value) {
    return ElMessage.warning('请先选择文件')
  }
  if (!docName.value) {
    return ElMessage.warning('请填写文档名')
  }
  uploading.value = true
  try {
    // 真在干活：切块 + 15 次 embedding 请求，通常 5~20 秒，别当卡死了
    const res = await uploadDocument(selectedFile.value, docName.value)
    uploadResult.value = res.data
    docExists.value = true
    ElMessage.success(`入库完成，共 ${res.data.chunks} 个块`)
  } finally {
    uploading.value = false
  }
}

async function handleDelete() {
  await ElMessageBox.confirm(
    `确定删除文档「${docName.value}」的全部块？删除后它就不参与检索了。`,
    '提示',
    { type: 'warning' }
  )
  const res = await delDocument(docName.value)
  docExists.value = false
  uploadResult.value = null
  ElMessage.success(`已删除 ${res.data.deletedRows} 行`)
}

// ---------------- 问答 ----------------
const question = ref('')
const asking = ref(false)
const answer = reactive({
  answer: '',
  citations: [],
  contextChunks: 0,
  contextChars: 0,
  error: null
})

/** 演示用的示例问题，都在 Day15 那篇文档里。点一下直接填进输入框，省得现场打字打错 */
const samples = [
  '断点续传依靠哪三个要素？',
  'RDB 和 AOF 的数据安全边界差在哪？',
  '主从第一次同步为什么必然是全量？'
]

/**
 * 把文本里的行内 Markdown 渲染成 HTML。
 *
 * 必须先 escapeHtml 再替换：
 * 顺序反过来的话，模型输出里的 < > 会原样进 DOM，等于给自己开个 XSS 口子。
 * 先整体转义，之后能通过 v-html 生效的就只剩我们自己加的那几个标签。
 *
 * 只处理两种最常见的：反引号包代码、双星号包粗体。
 * 不引 markdown 库是因为答案本身很短，引一个库要多背 30KB，不值。
 */
function escapeHtml(text) {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
}

function renderRich(text) {
  if (!text) {
    return ''
  }
  return escapeHtml(text)
    .replace(/`([^`\n]+)`/g, '<code>$1</code>')
    .replace(/\*\*([^*\n]+)\*\*/g, '<strong>$1</strong>')
}

const renderedAnswer = computed(() => renderRich(answer.answer))

/**
 * 上下文压缩比：原文多少字 → 送进模型多少字。
 * 只有"刚上传完、且引用确实来自这篇文档"两个条件都满足才算，
 * 否则（刷新过页面、或库里有多篇文档）宁可不显示，也不显示错的数。
 */
const compression = computed(() => {
  const sourceChars = uploadResult.value && uploadResult.value.chars
  const citedDoc = answer.citations.length ? answer.citations[0].docName : null
  if (!sourceChars || !citedDoc || citedDoc !== docName.value) {
    return ''
  }
  const percent = Math.round((answer.contextChars / sourceChars) * 100)
  return `${sourceChars} 字 → ${answer.contextChars} 字（${percent}%）`
})

async function handleAsk() {
  if (!question.value.trim()) {
    return ElMessage.warning('请输入问题')
  }
  // 先清空，否则上一次的答案会和新问题错位
  Object.assign(answer, { answer: '', citations: [], contextChunks: 0, contextChars: 0, error: null })
  asking.value = true
  try {
    const res = await askQuestion(question.value.trim())
    Object.assign(answer, res.data)
    if (res.data.error) {
      ElMessage.warning(res.data.error)
    }
  } finally {
    asking.value = false
  }
}
</script>

<style scoped>
.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-weight: 600;
}

.hint {
  font-size: 12px;
  font-weight: 400;
  color: #909399;
}

.meta {
  margin-top: 4px;
  font-size: 12px;
  color: #606266;
}

.ask-bar {
  margin-top: 10px;
  text-align: right;
}

.samples {
  margin-top: 10px;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}

.samples-label {
  font-size: 12px;
  color: #909399;
}

.sample {
  cursor: pointer;
}

.compression {
  margin-top: 10px;
  font-size: 12px;
  color: #e6a23c;
}

/* v-html 渲染出来的标签拿不到 scoped 的 data 属性，必须用 :deep 才吃得到样式 */
.answer-text :deep(code),
.cite-snippet :deep(code) {
  padding: 0 4px;
  background: #f0f2f5;
  border-radius: 3px;
  font-family: Consolas, Monaco, monospace;
  font-size: 13px;
  color: #c7254e;
}

.answer-text :deep(strong) {
  color: #303133;
}

.answer-text {
  white-space: pre-wrap;
  line-height: 1.8;
  font-size: 14px;
  color: #303133;
}

.cost {
  margin: 12px 0 8px;
  display: flex;
  gap: 8px;
}

.cite-collapse {
  margin-top: 4px;
}

.cite-item {
  padding: 8px 0;
  border-bottom: 1px dashed #ebeef5;
}

.cite-item:last-child {
  border-bottom: none;
}

.cite-head {
  display: flex;
  gap: 12px;
  align-items: center;
  font-size: 12px;
  color: #909399;
  margin-bottom: 6px;
}

.cite-no {
  color: #409eff;
  font-weight: 600;
}

.cite-id {
  margin-left: auto;
}

.cite-snippet {
  margin: 0;
  padding: 8px 10px;
  background: #f5f7fa;
  border-radius: 4px;
  font-family: Consolas, Monaco, monospace;
  font-size: 12px;
  line-height: 1.6;
  color: #606266;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
