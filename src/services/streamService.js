async function parseResponse(response) {
  const contentType = response.headers.get('content-type') || ''
  const data = contentType.includes('application/json')
    ? await response.json()
    : { message: await response.text() }

  if (!response.ok) {
    throw new Error(data.message || '请求失败')
  }

  return data
}

export async function openRtspStream(apiBase, rtspUrl) {
  const response = await fetch(`${apiBase.replace(/\/$/, '')}/streams/open`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json'
    },
    body: JSON.stringify({ rtspUrl })
  })

  return parseResponse(response)
}

export async function closeRtspStream(apiBase, streamId) {
  const response = await fetch(`${apiBase.replace(/\/$/, '')}/streams/${streamId}`, {
    method: 'DELETE'
  })

  return parseResponse(response)
}

export async function heartbeatRtspStream(apiBase, streamId) {
  const response = await fetch(`${apiBase.replace(/\/$/, '')}/streams/${streamId}/heartbeat`, {
    method: 'POST'
  })

  return parseResponse(response)
}

export async function releaseRtspStream(apiBase, streamId) {
  const response = await fetch(`${apiBase.replace(/\/$/, '')}/streams/${streamId}/release`, {
    method: 'POST'
  })

  return parseResponse(response)
}

export function releaseRtspStreamInBackground(apiBase, streamId) {
  fetch(`${apiBase.replace(/\/$/, '')}/streams/${streamId}/release`, {
    method: 'POST',
    keepalive: true
  }).catch(() => {})
}
