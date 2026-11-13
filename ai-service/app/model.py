"""Model boundary for third-party OpenAI-compatible LLM providers (e.g. NVIDIA NIM)."""
import json
import os
import time
import requests
from fastapi import HTTPException

TRANSIENT_STATUSES = frozenset({429, 502, 503, 504})


class NvidiaModel:
    def generate(self, instruction: str, data: dict, schema: dict) -> dict:
        key = (os.getenv('LLM_API_KEY') or os.getenv('NVIDIA_API_KEY') or '').strip()
        if not key:
            raise HTTPException(
                503,
                detail={'code': 'AI_NOT_CONFIGURED', 'message': 'Chưa cấu hình LLM_API_KEY / NVIDIA_API_KEY'},
            )
        base_url = (
            os.getenv('LLM_BASE_URL')
            or os.getenv('NVIDIA_BASE_URL')
            or 'https://integrate.api.nvidia.com/v1'
        ).rstrip('/')
        url = base_url + '/chat/completions'
        model_name = (
            os.getenv('LLM_MODEL')
            or os.getenv('NVIDIA_MODEL')
            or 'nvidia/nemotron-3-ultra-550b-a55b'
        ).strip()
        payload = {
            'model': model_name,
            'messages': [
                {
                    'role': 'system',
                    'content': instruction + ' Return only a JSON object matching this schema: ' + json.dumps(schema, ensure_ascii=False),
                },
                {'role': 'user', 'content': json.dumps(data, ensure_ascii=False)},
            ],
            'max_tokens': 8192,
            'temperature': 0.2,
            'response_format': {'type': 'json_object'},
        }
        if 'nemotron' in model_name.lower() or 'integrate.api.nvidia.com' in base_url.lower():
            payload['chat_template_kwargs'] = {'enable_thinking': False}

        timeout = float(os.getenv('LLM_TIMEOUT_SECONDS') or os.getenv('NVIDIA_TIMEOUT_SECONDS') or '90')
        for attempt in range(3):
            try:
                response = requests.post(url, headers={'Authorization': f'Bearer {key}'}, json=payload, timeout=timeout)
                if response.status_code in (401, 403):
                    raise HTTPException(503, detail={'code': 'AI_AUTH_FAILED', 'message': 'Nhà cung cấp AI từ chối API key'})
                if response.status_code in TRANSIENT_STATUSES:
                    if attempt < 2:
                        time.sleep(0.5 * (attempt + 1))
                        continue
                    if response.status_code == 429:
                        raise HTTPException(503, detail={'code': 'AI_RATE_LIMIT', 'message': 'API AI đã giới hạn lượt gọi; hãy thử lại sau'})
                    raise HTTPException(503, detail={'code': 'AI_UNAVAILABLE', 'message': 'Dịch vụ AI tạm thời quá tải; hãy thử lại sau'})
                response.raise_for_status()
                content = response.json()['choices'][0]['message']['content']
                return json.loads(content)
            except HTTPException:
                raise
            except requests.Timeout:
                raise HTTPException(504, detail={'code': 'AI_TIMEOUT', 'message': 'Nhà cung cấp AI phản hồi quá chậm'}) from None
            except requests.RequestException:
                raise HTTPException(503, detail={'code': 'AI_UNAVAILABLE', 'message': 'Không thể kết nối tới API AI'}) from None
            except (ValueError, KeyError, IndexError, TypeError):
                if attempt == 2:
                    raise HTTPException(502, detail={'code': 'AI_INVALID_OUTPUT', 'message': 'Model trả kết quả không hợp lệ'}) from None
