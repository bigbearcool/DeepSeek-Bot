/**
 * Phone attachments, through dsh's own attachment path — the same one the desktop chat uses:
 *  - an image goes into the prompt as `{ type: 'image', mediaType, data }`; dsh's session
 *    controller admits it (`attachments.admitPromptContent`) and checks type, size, pixels;
 *  - any other file is streamed into `fileUploads.uploadStream({ sessionId })` (the service
 *    behind dsh's `/api/session/uploadFileBinary`), and the prompt cites its `receiptId`.
 * The bridge never stores bytes on disk itself. An upload waits here (in memory, 10 minutes)
 * until the phone sends the message it belongs to.
 *
 * Reading back: history items carry attachment refs; only refs the bridge has handed out in
 * a history answer can be fetched, so a phone cannot name an arbitrary stored file.
 */
import { randomUUID } from 'node:crypto'
import { readFile } from 'node:fs/promises'

export const IMAGE_TYPES = ['image/png', 'image/jpeg', 'image/webp', 'image/gif']
export const MAX_UPLOAD_BYTES = 25 * 1024 * 1024
const UPLOAD_TTL_MS = 10 * 60 * 1000

/** Image media type from the first bytes, or null. dsh checks again on admission. */
export function sniffImage(bytes) {
  const b = bytes
  if (b.length >= 8 && b[0] === 0x89 && b[1] === 0x50 && b[2] === 0x4e && b[3] === 0x47) return 'image/png'
  if (b.length >= 3 && b[0] === 0xff && b[1] === 0xd8 && b[2] === 0xff) return 'image/jpeg'
  if (b.length >= 12 && String.fromCharCode(...b.subarray(0, 4)) === 'RIFF' && String.fromCharCode(...b.subarray(8, 12)) === 'WEBP') return 'image/webp'
  if (b.length >= 6 && String.fromCharCode(...b.subarray(0, 3)) === 'GIF') return 'image/gif'
  return null
}

/** A display name without any path, at most 120 characters. */
export function cleanName(name) {
  const leaf = String(name ?? '').split(/[\\/]/).pop().replace(/[\u0000-\u001f]/g, '').trim()
  return leaf.slice(0, 120) || undefined
}

/** One attachment ref as the phone sees it. */
export function publicRef(block) {
  const a = block?.attachment
  if (!a || typeof a.attachmentId !== 'string') return null
  if (block.type === 'image') {
    return { type: 'image', id: a.attachmentId, mediaType: a.mediaType ?? null, bytes: a.bytes ?? null, width: a.width ?? null, height: a.height ?? null, ...(a.name ? { name: a.name } : {}) }
  }
  if (block.type === 'file') return { type: 'file', id: a.attachmentId, name: a.name ?? '文件', bytes: a.bytes ?? null }
  return null
}

export function createAttachments({ ctx, now = () => Date.now() } = {}) {
  const service = name => { try { return ctx?.get?.(name) ?? ctx?.[name] } catch { return ctx?.[name] } }
  /** uploadId → { sessionId, image?: { mediaType, data, name }, file?: { receiptId, ref } , at } */
  const uploads = new Map()
  /** attachmentId → { type, ref } for refs the bridge has shown to a phone. */
  const shown = new Map()

  function sweep() {
    const cut = now() - UPLOAD_TTL_MS
    for (const [id, entry] of uploads) if (entry.at < cut) uploads.delete(id)
  }

  /** Store one upload for `sessionId` (the Bot's current Session or a group id). */
  async function upload({ sessionId, bytes, name }) {
    sweep()
    if (!(bytes instanceof Uint8Array) || bytes.length === 0) throw Object.assign(new Error('empty upload'), { status: 400, zh: '文件是空的' })
    if (bytes.length > MAX_UPLOAD_BYTES) throw Object.assign(new Error('file too large'), { status: 413, zh: '文件太大了，最多 25 MB' })
    const clean = cleanName(name)
    const mediaType = sniffImage(bytes)
    const uploadId = randomUUID()
    if (mediaType) {
      uploads.set(uploadId, { sessionId, at: now(), image: { mediaType, data: Buffer.from(bytes).toString('base64'), ...(clean ? { name: clean } : {}) } })
      return { uploadId, type: 'image', mediaType, bytes: bytes.length, ...(clean ? { name: clean } : {}) }
    }
    const fileUploads = service('fileUploads')
    if (typeof fileUploads?.uploadStream !== 'function') throw Object.assign(new Error('file uploads are not available on this computer'), { status: 503, zh: '这台电脑暂时收不了文件' })
    async function* once() { yield bytes }
    const staged = await fileUploads.uploadStream({ sessionId, data: once(), ...(clean ? { name: clean } : {}) })
    uploads.set(uploadId, { sessionId, at: now(), file: { receiptId: staged.receiptId, ref: staged.file } })
    return { uploadId, type: 'file', name: staged.file?.name ?? clean ?? '文件', bytes: staged.file?.bytes ?? bytes.length }
  }

  /** Prompt parts for the uploads a message names; each must be for this Session. */
  function parts(uploadIds, sessionId) {
    sweep()
    const ids = Array.isArray(uploadIds) ? uploadIds.filter(id => typeof id === 'string') : []
    return ids.map((id) => {
      const entry = uploads.get(id)
      if (!entry || entry.sessionId !== sessionId) throw Object.assign(new Error('upload not found'), { status: 400, zh: '附件已过期，请重新添加' })
      return entry.image ? { type: 'image', ...entry.image } : { type: 'file', receiptId: entry.file.receiptId }
    })
  }

  function used(uploadIds) {
    for (const id of Array.isArray(uploadIds) ? uploadIds : []) uploads.delete(id)
  }

  /** Remember refs a history answer shows, so they can be opened. */
  function remember(items) {
    for (const item of items ?? []) for (const a of item.attachments ?? []) if (a?.id && a._ref) shown.set(a.id, { type: a.type, ref: a._ref })
    for (const item of items ?? []) if (item.attachments) item.attachments = item.attachments.map(({ _ref, ...rest }) => rest)
    return items
  }

  /** Bytes of one attachment the phone was shown: { type, bytes, mediaType, name } or null. */
  async function read(id) {
    const entry = shown.get(id)
    if (!entry) return null
    const attachments = service('attachments')
    if (!attachments) throw Object.assign(new Error('attachments are not available on this computer'), { status: 503 })
    if (entry.type === 'image') {
      const path = attachments.imageHostPath?.(entry.ref)
      if (!path) return null
      return { bytes: await readFile(path), mediaType: entry.ref.mediaType ?? 'application/octet-stream', name: entry.ref.name }
    }
    const chunks = []
    for await (const chunk of attachments.readFileStream(entry.ref)) chunks.push(chunk)
    return { bytes: Buffer.concat(chunks), mediaType: 'application/octet-stream', name: entry.ref.name }
  }

  return { upload, parts, used, remember, read, shownCount: () => shown.size, pending: () => uploads.size }
}
