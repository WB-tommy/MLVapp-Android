## Prompt-Driven Development

This project follows a **spec-driven / LLM-assisted workflow**.  
All key project context is captured in prompt files for clarity and automation.

| File | Purpose | Change Frequency |
|------|----------|------------------|
| **plan.prompt.md** | The core intent and architecture — the "North Star" that rarely changes. | Rarely |
| **specify.prompt.md** | Technical and functional feature specs for implementation. | Occasionally |
| **tasks.prompt.md** | Live, evolving TODO list — updated as work progresses. | Frequently |

These files guide both human and AI contributors to maintain consistent direction, context, and priorities.
All prompt files are under prompts/ folder.
Don't edit the code before the user asks to change.
