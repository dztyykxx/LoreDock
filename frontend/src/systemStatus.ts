export interface SystemStatus {
  service: string
  status: 'UP'
}

const apiBaseUrl = import.meta.env.VITE_API_BASE_URL ?? ''

export async function fetchSystemStatus(): Promise<SystemStatus> {
  const response = await fetch(`${apiBaseUrl}/api/v1/system/status`)
  if (!response.ok) {
    throw new Error(`system status request failed: ${response.status}`)
  }
  return response.json() as Promise<SystemStatus>
}
