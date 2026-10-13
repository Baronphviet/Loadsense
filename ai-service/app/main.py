from fastapi import FastAPI, HTTPException
from .schemas import (ParsePromptRequest, ParsePromptResponse, GenerateMockDataRequest,
                      GenerateMockDataResponse, AnalyzeLogRequest, AnalyzeLogResponse)

app = FastAPI(title="LoadSense AI Contract", version="0.1.0",
              description="Contract skeleton. LocalAI/Ollama implementation is deferred.")

def deferred():
    raise HTTPException(status_code=501, detail={"code": "AI_DEFERRED", "message": "LocalAI chưa được triển khai"})

@app.get("/health")
def health():
    return {"status": "UP", "aiEnabled": False, "modelStatus": "DEFERRED"}

@app.post("/parse-prompt", response_model=ParsePromptResponse)
def parse_prompt(request: ParsePromptRequest):
    deferred()

@app.post("/generate-mockdata", response_model=GenerateMockDataResponse)
def generate_mockdata(request: GenerateMockDataRequest):
    deferred()

@app.post("/analyze-log", response_model=AnalyzeLogResponse)
def analyze_log(request: AnalyzeLogRequest):
    deferred()
