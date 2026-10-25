// ReadFlow's JNI adapter for the Apache-2.0 Misaki Go port. Only the neural
// fallback is imported: this library contains no eSpeak code, data or loader.
package main

/*
#include <stdint.h>
#include <stdlib.h>
*/
import "C"

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"sync"
	"unsafe"

	g2p "github.com/androiddrew/go-g2p"
	"github.com/androiddrew/go-g2p/fallback/neural"
)

type frontend struct {
	mu       sync.Mutex
	engine   *g2p.Engine
	fallback *neural.Engine
}

var registry = struct {
	sync.Mutex
	next    uint64
	entries map[uint64]*frontend
}{entries: make(map[uint64]*frontend)}

// All exported functions return owned UTF-8 JSON. The caller must release the
// response with ReadFlowMisakiFree, including error responses. No Go pointer
// crosses the C boundary.
func response(f func() (any, error)) (result *C.char) {
	defer func() {
		if p := recover(); p != nil {
			data, _ := json.Marshal(map[string]any{"error": fmt.Sprintf("Misaki failed: %v", p)})
			result = C.CString(string(data))
		}
	}()
	v, err := f()
	if err != nil {
		v = map[string]any{"error": err.Error()}
	}
	data, err := json.Marshal(v)
	if err != nil {
		data = []byte(`{"error":"Unable to encode Misaki result"}`)
	}
	return C.CString(string(data))
}

//export ReadFlowMisakiCreate
func ReadFlowMisakiCreate(library *C.char, threads C.int) *C.char {
	return response(func() (any, error) {
		if threads < 1 || threads > 4 {
			return nil, errors.New("Misaki threads must be between 1 and 4")
		}
		fb, err := neural.New(neural.Config{ORTLibrary: C.GoString(library), Threads: int(threads)})
		if err != nil {
			return nil, err
		}
		e, err := g2p.New(g2p.Config{ORTLibrary: C.GoString(library), Threads: int(threads), Fallback: fb})
		if err != nil {
			return nil, errors.Join(err, fb.Close())
		}
		registry.Lock()
		defer registry.Unlock()
		registry.next++
		id := registry.next
		registry.entries[id] = &frontend{engine: e, fallback: fb}
		return map[string]any{"handle": id}, nil
	})
}

func lookup(handle uint64) (*frontend, error) {
	registry.Lock()
	defer registry.Unlock()
	f := registry.entries[handle]
	if f == nil {
		return nil, errors.New("Misaki frontend is closed")
	}
	return f, nil
}

//export ReadFlowMisakiPhonemize
func ReadFlowMisakiPhonemize(handle C.uint64_t, text *C.char) *C.char {
	return response(func() (any, error) {
		f, err := lookup(uint64(handle))
		if err != nil {
			return nil, err
		}
		f.mu.Lock()
		defer f.mu.Unlock()
		if f.engine == nil {
			return nil, errors.New("Misaki frontend is closed")
		}
		r, err := f.engine.Phonemize(context.Background(), g2p.Request{Text: C.GoString(text), Dialect: g2p.US})
		if err != nil {
			return nil, err
		}
		// Never silently synthesize an incomplete sentence or switch to eSpeak.
		if !r.Complete {
			return nil, errors.New("Misaki returned an incomplete pronunciation")
		}
		return map[string]any{"phonemes": r.Phonemes, "complete": true, "fallbackCalls": len(r.FallbackCalls)}, nil
	})
}

//export ReadFlowMisakiClose
func ReadFlowMisakiClose(handle C.uint64_t) *C.char {
	return response(func() (any, error) {
		registry.Lock()
		f := registry.entries[uint64(handle)]
		delete(registry.entries, uint64(handle))
		registry.Unlock()
		if f == nil {
			return map[string]any{"closed": true}, nil
		}
		f.mu.Lock()
		defer f.mu.Unlock()
		err := errors.Join(f.engine.Close(), f.fallback.Close())
		f.engine, f.fallback = nil, nil
		return map[string]any{"closed": true}, err
	})
}

//export ReadFlowMisakiFree
func ReadFlowMisakiFree(value *C.char) { C.free(unsafe.Pointer(value)) }

func main() {}
