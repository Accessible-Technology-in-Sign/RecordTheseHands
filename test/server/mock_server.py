from pathlib import Path

from fastapi import FastAPI, Form, HTTPException
from fastapi.responses import FileResponse

app = FastAPI()

FIXTURES_DIR = Path(__file__).parent / "fixtures"


@app.post("/check_version")
async def check_version():
    return "You are logged in!"


@app.post("/register_login")
async def register_login():
    return "Registration successful!"


@app.post("/prompts")
async def get_prompts(
    app_version: str = Form(default=""),
    login_token: str = Form(default=""),
):
    fixture_path = FIXTURES_DIR / "prompts.json"
    if not fixture_path.exists():
        raise HTTPException(
            status_code=404, detail="prompts.json not found in fixtures"
        )
    return FileResponse(fixture_path, media_type="application/json")


@app.post("/resource")
async def get_resource(
    path: str = Form(...),
    app_version: str = Form(default=""),
    login_token: str = Form(default=""),
):
    fixture_path = (FIXTURES_DIR / path).resolve()

    # Prevent path traversal outside fixtures dir
    if not fixture_path.is_relative_to(FIXTURES_DIR):
        raise HTTPException(status_code=400, detail="Invalid path")

    if not fixture_path.exists():
        raise HTTPException(
            status_code=404, detail=f"Fixture not found: {path}"
        )

    return FileResponse(fixture_path)


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host="0.0.0.0", port=8080)
