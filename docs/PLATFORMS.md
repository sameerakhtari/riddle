# Platform roadmap

The product name is platform-neutral so each client can live under the same identity.

## Android

**Status:** active

The Android client is the reference implementation and currently contains the full diary experience, S Pen integration, on-device inference, model management, memory, history, and diagnostics.

## iOS and iPadOS

**Status:** planned

A future Apple client should live under `ios/` and share product behavior rather than Android implementation details. Apple Pencil input, local model support, privacy behavior, conversation storage, and the complete-answer ink animation should match the product contract documented in this repository.

## Desktop and web

**Status:** exploratory

Desktop or web editions may use pointer or drawing-tablet input and a local or hosted model endpoint. They should preserve the same local-first storage and explicit-provider principles.

## reMarkable

**Status:** preserved upstream implementation

The inherited reMarkable source is archived under `remarkable/`. It remains useful as the origin of the concept and as a separate hardware-specific implementation, but it is not the active product root.
