"""Model boundary; a local provider can implement the same generate interface."""
import json
import os
import requests
from fastapi import HTTPException


class NvidiaModel:
    def generate(self, instruction: str, data: dict, schema: dict) -> dict:
        key = os.getenv('NVIDIA_API_KEY', '').strip()
        if not key:
            raise HTTPException(503, detail={'code': 'AI_NOT_CONFIGURED', 'message': 'Chưa cấu hình NVIDIA_API_KEY'})
        url = os.getenv('NVIDIA_BASE_URL', 'https://integrate.api.nvidia.com/v1').rstrip('/') + '/chat/completions'
        try:
            response = requests.post(url, headers={'Authorization': f'Bearer {key}'}, json={
                'model': os.getenv('NVIDIA_MODEL', 'nvidia/nemotron-3-ultra-550b-a55b'),
                'messages': [{'role': 'system', 'content': instruction + ' Return only a JSON object matching this schema: ' + json.dumps(schema, ensure_ascii=False)},
                             {'role': 'user', 'content': json.dumps(data, ensure_ascii=False)}],
                'max_tokens': 8192, 'temperature': 0.2,
                'chat_template_kwargs': {'enable_thinking': False},
            }, timeout=float(os.getenv('NVIDIA_TIMEOUT_SECONDS', '90')))
            if response.status_code == 429:
                raise HTTPException(503, detail={'code': 'AI_RATE_LIMIT', 'message': 'NVIDIA đã giới hạn lượt gọi; hãy thử lại sau'})
            if response.status_code in (401, 403):
                raise HTTPException(503, detail={'code': 'AI_AUTH_FAILED', 'message': 'NVIDIA từ chối API key'})
            response.raise_for_status()
            content = response.json()['choices'][0]['message']['content']
            return json.loads(content)
        except requests.Timeout:
            raise HTTPException(504, detail={'code': 'AI_TIMEOUT', 'message': 'NVIDIA phản hồi quá chậm'}) from None
        except requests.RequestException:
            raise HTTPException(503, detail={'code': 'AI_UNAVAILABLE', 'message': 'Không thể kết nối NVIDIA'}) from None
        except (ValueError, KeyError, IndexError, TypeError):
            raise HTTPException(502, detail={'code': 'AI_INVALID_OUTPUT', 'message': 'Model trả kết quả không hợp lệ'}) from None
