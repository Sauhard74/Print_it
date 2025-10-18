# Supported IPP Attributes

This document lists all IPP attributes supported by the Android Virtual Printer.

## Attribute Priority Order

The printer applies IPP attributes in the following priority order (highest priority last):

1. **Default Attributes** (Lowest Priority) - Built-in printer capabilities
2. **Custom Attributes from JSON** (Medium Priority) - User-defined attributes from Settings → Custom IPP Attributes
3. **Plugin Overrides** (Highest Priority) - Dynamic attribute modification via plugins

**Example:** If you set `printer-name` via plugin, it will override any custom attribute file setting, which in turn overrides the default.

---

## Default Printer Attributes

### Basic Printer Information
| Attribute | Type | Default Value | Description |
|-----------|------|---------------|-------------|
| `printer-name` | name | "Virtual Printer" | Printer name displayed to clients |
| `printer-state` | enum | idle | Current printer state (idle/processing/stopped) |
| `printer-state-reasons` | keyword | "none" | Reasons for current state |
| `printer-is-accepting-jobs` | boolean | true | Whether printer accepts new jobs |
| `printer-uri` | uri | `ipp://<device-ip>:631/` | Printer's IPP endpoint |
| `printer-location` | text | "Mobile Device" | Physical location of printer |
| `printer-info` | text | "Virtual Printer - Mobile PDF Printer" | Human-readable printer description |
| `printer-make-and-model` | text | "Virtual Printer v1.0" | Manufacturer and model information |
| `printer-up-time` | integer | Current uptime | Seconds since printer started |
| `printer-uri-supported` | uri | `ipp://<device-ip>:631/` | List of supported URIs |
| `queued-job-count` | integer | 0 | Number of jobs currently queued |

### Character Set and Language
| Attribute | Type | Default Value | Description |
|-----------|------|---------------|-------------|
| `charset-configured` | charset | "utf-8" | Configured character set |
| `charset-supported` | charset | "utf-8" | Supported character sets |
| `natural-language-configured` | naturalLanguage | "en" | Configured language |
| `generated-natural-language-supported` | naturalLanguage | "en" | Supported languages |

### IPP Protocol Support
| Attribute | Type | Default Value | Description |
|-----------|------|---------------|-------------|
| `ipp-versions-supported` | keyword | "1.1", "2.0" | Supported IPP versions |
| `uri-security-supported` | keyword | "none" | Security mechanisms |
| `uri-authentication-supported` | keyword | "none" | Authentication methods |
| `compression-supported` | keyword | "none" | Supported compression methods |
| `pdl-override-supported` | keyword | "not-attempted" | PDL override behavior |

### Document Format Support
| Attribute | Type | Default Value | Description |
|-----------|------|---------------|-------------|
| `document-format-supported` | mimeMediaType | Multiple formats | Supported document formats |
| `document-format` | mimeMediaType | "application/pdf" | Current document format |
| `document-format-default` | mimeMediaType | "application/pdf" | Default document format |

**Supported Formats:**
- `application/pdf` - PDF documents
- `application/octet-stream` - Binary data
- `application/vnd.cups-raw` - CUPS raw format
- `application/vnd.cups-pdf` - CUPS PDF format
- `image/jpeg` - JPEG images
- `image/png` - PNG images
- `text/plain` - Plain text

### Media Support
| Attribute | Type | Default Value | Description |
|-----------|------|---------------|-------------|
| `media-default` | keyword | "iso_a4_210x297mm" | Default media size |
| `media-supported` | keyword | Multiple sizes | Supported media sizes |

**Supported Media Sizes:**
- `iso_a4_210x297mm` - A4 (210 x 297 mm)
- `iso_a5_148x210mm` - A5 (148 x 210 mm)
- `na_letter_8.5x11in` - US Letter (8.5 x 11 inches)
- `na_legal_8.5x14in` - US Legal (8.5 x 14 inches)

### Job Attributes
| Attribute | Type | Default Value | Description |
|-----------|------|---------------|-------------|
| `job-sheets-default` | keyword | "none" | Default banner pages |
| `job-sheets-supported` | keyword | "none", "standard" | Supported banner pages |

### Operations Support
| Attribute | Type | Default Value | Description |
|-----------|------|---------------|-------------|
| `operations-supported` | enum | Multiple operations | Supported IPP operations |

**Supported Operations:**
- `Print-Job` (0x0002) - Print a document
- `Validate-Job` (0x0004) - Validate job attributes
- `Create-Job` (0x0005) - Create a job without document
- `Send-Document` (0x0006) - Send document to created job
- `Cancel-Job` (0x0008) - Cancel a print job
- `Get-Job-Attributes` (0x0009) - Get job status
- `Get-Printer-Attributes` (0x000B) - Get printer capabilities

### Printer Capabilities
| Attribute | Type | Default Value | Description |
|-----------|------|---------------|-------------|
| `color-supported` | boolean | true | Color printing capability |
| `sides-supported` | keyword | Not set by default | Duplex printing support (can be added via plugins) |

---

## Custom Attributes via JSON

You can override any default attribute by creating a JSON file with this structure:

```json
{
  "printer-attributes": {
    "printer-name": "My Custom Printer",
    "printer-location": "Office Room 101",
    "printer-info": "Custom Printer Info",
    "printer-make-and-model": "Custom Model v2.0",
    "color-supported": false,
    "media-supported": [
      "iso_a4_210x297mm",
      "na_letter_8.5x11in",
      "iso_a3_297x420mm"
    ]
  }
}
```

### How to Apply Custom Attributes

1. Open the Android Virtual Printer app
2. Go to **Settings** → **Custom IPP Attributes**
3. Tap **Import Attributes**
4. Select your JSON file
5. The custom attributes will override defaults

---

## Plugin-Based Attribute Override

The `AttributeOverridePlugin` provides the **highest priority** attribute modification:

### Configurable Attributes via Plugin

| Setting | Attribute Modified | Type | Description |
|---------|-------------------|------|-------------|
| Enable Override | - | Toggle | Turn plugin on/off |
| Printer Name | `printer-name` | Text | Override printer name |
| Printer Location | `printer-location` | Text | Override location |
| Printer Info | `printer-info` | Text | Override info text |
| Printer Model | `printer-make-and-model` | Text | Override model |
| Max Jobs | `queued-job-count` | Number (1-1000) | Limit job queue |
| Color Support | `color-supported` | Boolean | Enable/disable color |
| Duplex Support | `sides-supported`, `sides-default` | Boolean | Enable duplex printing |
| Media Sizes | `media-supported` | Comma-separated list | Custom paper sizes |

### Duplex Support

When duplex is enabled via plugin, the printer advertises:
- `sides-supported`: `one-sided`, `two-sided-long-edge`, `two-sided-short-edge`
- `sides-default`: `one-sided`

---

## Attribute Validation

### Required Attributes (Per RFC 8011)

All of these are **automatically** provided by the default attributes:
- ✅ `printer-uri-supported`
- ✅ `uri-security-supported`
- ✅ `uri-authentication-supported`
- ✅ `printer-name`
- ✅ `printer-state`
- ✅ `printer-state-reasons`
- ✅ `ipp-versions-supported`
- ✅ `operations-supported`
- ✅ `charset-configured`
- ✅ `charset-supported`
- ✅ `natural-language-configured`
- ✅ `generated-natural-language-supported`
- ✅ `document-format-supported`
- ✅ `printer-is-accepting-jobs`
- ✅ `queued-job-count`
- ✅ `pdl-override-supported`
- ✅ `printer-up-time`

### Attribute Behavior

All attributes listed in this document are **functional** and affect printer behavior:

- `printer-is-accepting-jobs: false` → Printer will **reject new jobs**
- `color-supported: false` → Clients will **not offer color options**
- `media-supported` → Clients will **only show listed paper sizes**
- `document-format-supported` → Printer will **reject unsupported formats**
- `operations-supported` → Clients will **disable unavailable operations**
- `sides-supported` → Clients will **show duplex printing options**

---

## Testing Attributes

### View Current Attributes

Use `ipptool` to query the printer:

```bash
ipptool -tv ipp://<device-ip>:631/ get-printer-attributes.test
```

### Test Custom Attributes

1. Create a JSON file with your custom attributes
2. Import via Settings → Custom IPP Attributes
3. Query the printer to verify attributes changed
4. Print a test page to verify behavior

### Test Plugin Override

1. Enable `AttributeOverridePlugin` in Settings → Plugins
2. Configure plugin settings (e.g., change printer name, enable duplex)
3. Query printer attributes - should show plugin values
4. Print from a client that supports duplex to verify functionality

---

## Notes

- **Priority is enforced strictly**: Plugins always win, then custom attributes, then defaults
- **All attributes are live**: Changes take effect immediately without restart
- **IPP compliant**: All required RFC 8011 attributes are always present
- **Client compatibility**: Attributes control what clients display and allow
- **Validation**: Invalid attribute values may be ignored or cause fallback to defaults

---

## Example Use Cases

### Use Case 1: Black & White Only Printer
**Method:** Custom Attributes JSON
```json
{
  "printer-attributes": {
    "printer-name": "BW Office Printer",
    "color-supported": false
  }
}
```
**Result:** Clients will not show color printing options.

### Use Case 2: Test Duplex Printing
**Method:** AttributeOverridePlugin
- Enable plugin
- Set "Duplex Support" → ON
**Result:** Printer advertises duplex capabilities, clients show two-sided options.

### Use Case 3: Restrict Paper Sizes
**Method:** Custom Attributes JSON or Plugin
```json
{
  "printer-attributes": {
    "media-supported": ["na_letter_8.5x11in"]
  }
}
```
**Result:** Only US Letter size available to clients.

---

## Support

For issues with custom attributes:
1. Check JSON syntax (must be valid JSON)
2. Verify attribute names match exactly (case-sensitive)
3. Check logs: `adb logcat -s PrinterService:D`
4. Test with default attributes first, then add customizations
5. Remember priority order: Plugins override everything

