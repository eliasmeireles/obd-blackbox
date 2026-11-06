// Package web embeds the live dashboard served by the API at "/".
package web

import (
	"embed"
	"io/fs"
)

//go:embed static
var static embed.FS

// Static returns the dashboard files (index.html, app.js, styles.css) rooted at static/.
func Static() fs.FS {
	sub, err := fs.Sub(static, "static")
	if err != nil {
		panic(err)
	}
	return sub
}
