import request from '@/utils/request'

// 把一篇文档按 JSON 文本入库（前端一般用不上，留给调试和脚本）
export function indexDocument(data) {
  return request({
    url: '/merchant/rag/index',
    method: 'post',
    data: data
  })
}

// 上传文件入库。
// repeatSubmit: false 是必须的：若依的防重复提交会把 config.data 拿去 JSON.stringify，
// 而 FormData 序列化出来永远是 "{}"，两次上传的判定 key 完全相同，
// 短时间连传两次会被误判成重复提交直接拦掉。
export function uploadDocument(file, docName) {
  const form = new FormData()
  form.append('file', file)
  if (docName) {
    form.append('docName', docName)
  }
  return request({
    url: '/merchant/rag/upload',
    method: 'post',
    data: form,
    headers: { repeatSubmit: false }
  })
}

// 提问。整条链路只有这一个请求会调用大模型，耗时以秒计
export function askQuestion(question) {
  return request({
    url: '/merchant/rag/ask',
    method: 'get',
    params: { question: question }
  })
}

// 这篇文档是否已经入过库
export function existsDocument(docName) {
  return request({
    url: '/merchant/rag/exists',
    method: 'get',
    params: { docName: docName }
  })
}

// 删除一篇文档的全部块（父块 + 子块一起删）
export function delDocument(docName) {
  return request({
    url: '/merchant/rag/document',
    method: 'delete',
    params: { docName: docName }
  })
}
