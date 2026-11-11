import { ragApi } from '@/services/api'

/**
 * T17 · U2：招标文件上传（列表页与发布页共用）。
 *
 * 后端契约（RagUploadController）：POST /api/rag/upload，multipart 带 tender_id + file，
 * 返回 TenderDocument（snake_case：chunk_count / file_name / status ...）。
 *
 * 这里只做两件事：把文件传给 ragApi.upload；把后端 message 抽成一句能直接弹给人的话。
 */

/** 允许的扩展名，与后端 RagUploadService.ALLOWED_EXT 保持一致 */
export const UPLOAD_ACCEPT = '.pdf,.docx,.pptx,.xlsx,.txt'

/** 单文件上限 50MB，与后端 MAX_BYTES 一致 */
export const UPLOAD_MAX_BYTES = 50 * 1024 * 1024

/**
 * 上传一个招标文件到指定招标项目。
 * 成功返回后端 TenderDocument；失败抛出 Error，message 优先取后端返回的 message。
 */
export async function uploadTenderDocument(tenderId, file) {
  if (!tenderId) {
    throw new Error('缺少招标项目信息，无法上传')
  }
  if (!file) {
    throw new Error('请先选择要上传的文件')
  }
  try {
    return await ragApi.upload(tenderId, file)
  } catch (e) {
    const body = e && e.response && e.response.data
    const msg = (body && body.message) || (e && e.message) || '上传失败'
    throw new Error(msg)
  }
}

/** 选中文件后的前置校验（扩展名 / 大小 / 空文件）。返回 null 表示通过，否则返回错误文案。 */
export function validateUploadFile(file) {
  if (!file) return '请先选择要上传的文件'
  const name = (file.name || '').toLowerCase()
  const dot = name.lastIndexOf('.')
  const ext = dot < 0 ? '' : name.slice(dot)
  if (UPLOAD_ACCEPT.split(',').indexOf(ext) === -1) {
    return '不支持的文件格式，仅支持 pdf / docx / pptx / xlsx / txt'
  }
  if (file.size === 0) return '文件为空'
  if (file.size > UPLOAD_MAX_BYTES) return '文件过大，最大 50MB'
  return null
}

/** 把上传结果压成一句人话：「已上传，X 条片段入库」 */
export function uploadResultText(doc) {
  const chunks = doc && doc.chunk_count != null ? doc.chunk_count : 0
  return `已上传，${chunks} 条片段入库`
}
